package dev.mahali.hatch.engine

import dev.mahali.hatch.core.AnswerOutcome
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.PromptActionId
import dev.mahali.hatch.core.PromptChoice
import dev.mahali.hatch.core.PromptChoices
import dev.mahali.hatch.core.PromptFallback
import dev.mahali.hatch.core.PromptState
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.PromptView
import dev.mahali.hatch.core.allBlocks
import dev.mahali.hatch.core.WindowRef
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class PromptSource(val connection: Any, val generation: Long, val server: String, val revision: InputRevision)
internal data class PromptObservation(val source: PromptSource, val process: dev.mahali.hatch.protocol.ForegroundProcess, val prompt: ParsedPrompt?)
internal interface PromptRuntime {
    val state: StateFlow<AppState>
    val hints: Flow<PromptTarget>
    fun source(target: PromptTarget): PromptSource?
    suspend fun read(target: PromptTarget): PromptObservation
    suspend fun answer(target: PromptTarget, observation: PromptObservation, number: Int, valid: () -> Boolean, sending: () -> Unit): AnswerOutcome
}

public class PromptActions internal constructor(
    private val runtime: PromptRuntime,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val decodeDispatch: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    initiallyEnabled: Boolean = true,
) {
    private class Entry {
        var job: Job? = null
        var expiry: Job? = null
        var signature: Any? = null
        var observation: PromptObservation? = null
        var offeredAt = 0L
        var lastProbe = Long.MIN_VALUE / 2
        var attempted: String? = null
        var active = AtomicBoolean(true)
        var readGeneration = 0L
        var dirty = false
        val actions = mutableMapOf<PromptActionId, Int>()
    }

    private val lock = Any()
    private val records = mutableMapOf<PromptTarget, Entry>()
    private val hostLimits = mutableMapOf<HostId, Mutex>()
    private val readSlots = Semaphore(1)
    private var enabled = initiallyEnabled
    private val flow = MutableStateFlow<Map<PromptTarget, PromptState>>(emptyMap())
    public val entries: StateFlow<Map<PromptTarget, PromptState>> get() = flow

    init {
        scope.launch { runtime.state.collect { reconcile(it) } }
        scope.launch { runtime.hints.collect { schedule(it, explicit = false) } }
    }

    public fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (enabled == value) return
            enabled = value
            if (!value) {
                records.values.forEach { invalidate(it); it.job?.cancel() }
                records.clear()
                flow.value = emptyMap()
            }
        }
        if (value) reconcile(runtime.state.value)
    }

    public fun inspect(target: PromptTarget): Unit = schedule(target, explicit = true)

    private fun schedule(target: PromptTarget, explicit: Boolean) {
        synchronized(lock) {
            if (!enabled && !explicit) return
            if (runtime.source(target) == null) return
            val entry = records.getOrPut(target) { Entry() }
            if (entry.job?.isActive == true) { entry.dirty = true; return }
            if (flow.value[target] is PromptState.Checking || flow.value[target] is PromptState.Sending) return
            if (records.count { (key, value) -> key.window.host == target.window.host && value.job?.isActive == true } >= 32) {
                publish(target, PromptState.OpenOnly(PromptFallback.InputBusy)); return
            }
            val host = hostLimits.getOrPut(target.window.host) { Mutex() }
            val wait = maxOf(150L, entry.lastProbe + 2_000 - clock())
            val readGeneration = ++entry.readGeneration
            val attempts = if (entry.attempted != null) 0..0 else 0..2
            entry.job = scope.launch(decodeDispatch) {
                delay(wait)
                try {
                    var result: PromptObservation? = null
                    for (retry in attempts) {
                        if (retry > 0) delay(if (retry == 1) 350 else 1_000)
                        result = host.withLock {
                            readSlots.withPermit {
                                synchronized(lock) { entry.lastProbe = clock() }
                                runtime.read(target)
                            }
                        }
                        if (result.prompt != null || result.process.promptAgent() == null) break
                    }
                    complete(target, entry, readGeneration, result)
                } catch (error: TimeoutCancellationException) {
                    unavailable(target, entry, readGeneration, PromptFallback.Incomplete)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val reason = if (error is dev.mahali.hatch.protocol.PromptScreenUnavailable && error.reason == "too_large") PromptFallback.TooLarge else PromptFallback.Unsupported
                    unavailable(target, entry, readGeneration, reason)
                } finally {
                    synchronized(lock) {
                        if (records[target] === entry && entry.readGeneration == readGeneration) {
                            entry.job = null
                            if (entry.dirty) { entry.dirty = false; schedule(target, explicit = false) }
                        }
                    }
                }
            }
        }
    }

    /** [answer] in the watcher's own scope, so leaving the screen that asked cannot cancel a send halfway. */
    public fun answerAsync(action: PromptActionId): Deferred<AnswerOutcome> = scope.async { answer(action) }

    public suspend fun answer(action: PromptActionId): AnswerOutcome {
        val claim = synchronized(lock) {
            val found = records.entries.firstOrNull { action in it.value.actions }
                ?: return AnswerOutcome.NotSent(PromptFallback.AlreadyAnswered)
            val target = found.key
            val entry = found.value
            val offered = flow.value[target] as? PromptState.Offered
                ?: return AnswerOutcome.NotSent(PromptFallback.AlreadyAnswered)
            val observation = entry.observation ?: return AnswerOutcome.NotSent(PromptFallback.Changed)
            if (clock() - entry.offeredAt >= 60_000 || runtime.source(target) != observation.source) {
                invalidate(entry)
                publish(target, PromptState.OpenOnly(PromptFallback.Expired))
                return AnswerOutcome.NotSent(PromptFallback.Expired)
            }
            val number = entry.actions.getValue(action)
            entry.actions.clear()
            entry.expiry?.cancel()
            entry.readGeneration++
            entry.job?.cancel()
            entry.attempted = observation.prompt?.fingerprint
            publish(target, PromptState.Checking(offered.view))
            Claim(target, entry, observation, number, offered.view, entry.active)
        }
        val outcome = try {
            // Known Claude menus initially ignore numeric accelerators. Delay
            // before verification, never after the final screen comparison.
            if (claim.observation.prompt?.agent == PromptAgent.Claude) {
                delay((claim.entry.offeredAt + 650 - clock()).coerceAtLeast(0))
            }
            runtime.answer(claim.target, claim.observation, claim.number, { claim.active.get() }) {
                synchronized(lock) {
                    if (records[claim.target] === claim.entry) publish(claim.target, PromptState.Sending(claim.view))
                }
            }
        } catch (error: CancellationException) {
            synchronized(lock) {
                if (records[claim.target] === claim.entry) {
                    invalidate(claim.entry)
                    publish(claim.target, PromptState.OpenOnly(PromptFallback.Unconfirmed))
                }
            }
            throw error
        }
        synchronized(lock) {
            if (records[claim.target] === claim.entry) {
                when (outcome) {
                    AnswerOutcome.InputAcknowledged -> publish(claim.target, PromptState.AwaitingChange(claim.view))
                    AnswerOutcome.DeliveryUnconfirmed -> publish(claim.target, PromptState.OpenOnly(PromptFallback.Unconfirmed))
                    is AnswerOutcome.NotSent -> {
                        claim.entry.attempted = null
                        publish(claim.target, PromptState.OpenOnly(outcome.reason))
                    }
                }
            }
        }
        if (outcome == AnswerOutcome.InputAcknowledged) {
            scope.launch {
                delay(400)
                synchronized(lock) { if (records[claim.target] === claim.entry) schedule(claim.target, explicit = false) }
            }
        }
        return outcome
    }

    private data class Claim(val target: PromptTarget, val entry: Entry, val observation: PromptObservation, val number: Int, val view: PromptView, val active: AtomicBoolean)

    private fun reconcile(state: AppState) {
        synchronized(lock) {
            if (!enabled) return
            val targets = buildMap {
                for ((hostId, host) in state.byHost) for (session in host.sessionsById.values) {
                    for (windowId in session.windows) {
                        val window = host.windowsById[windowId] ?: continue
                        for (block in window.allBlocks()) put(hostId to block, PromptTarget(WindowRef(hostId, session.id, windowId), block))
                    }
                }
            }
            val removed = records.keys.filter { target ->
                targets[target.window.host to target.block] != target ||
                    records[target]?.signature != null && state.byHost[target.window.host]?.blocksById?.get(target.block)?.status?.values?.none { it.state == ProgramState.Blocked } == true
            }
            removed.forEach { target -> records.remove(target)?.let { invalidate(it); it.job?.cancel() }; flow.value = flow.value - target }
            records.forEach { (target, entry) ->
                if (runtime.source(target) != entry.observation?.source && entry.observation != null) {
                    invalidate(entry)
                    publish(target, PromptState.OpenOnly(PromptFallback.Changed))
                }
            }
            hostLimits.keys.retainAll(state.hosts.toSet())
            for ((hostId, host) in state.byHost) {
                if (host.link != Link.Online) continue
                for ((blockId, block) in host.blocksById) {
                    val blocked = block.status.values.filter { it.state == ProgramState.Blocked }
                    if (blocked.isEmpty()) continue
                    val target = targets[hostId to blockId] ?: continue
                    val signature = blocked.map { listOf(it.id, it.kind, it.title, it.message, it.app, it.effectiveApp) } to runtime.source(target)
                    val entry = records.getOrPut(target) { Entry() }
                    if (entry.signature != signature) { entry.signature = signature; schedule(target, explicit = false) }
                }
            }
        }
    }

    private fun complete(target: PromptTarget, entry: Entry, readGeneration: Long, observation: PromptObservation?) {
        synchronized(lock) {
            if (records[target] !== entry || entry.readGeneration != readGeneration || observation == null || runtime.source(target) != observation.source) return
            invalidate(entry)
            entry.observation = observation
            entry.active = AtomicBoolean(true)
            val prompt = observation.prompt
            if (prompt == null || prompt.fingerprint == entry.attempted) {
                publish(target, PromptState.OpenOnly(PromptFallback.Unsupported)); return
            }
            val choices = prompt.choices.map { choice ->
                val id = PromptActionId(UUID.randomUUID().toString())
                entry.actions[id] = choice.number
                PromptChoice(choice.label, id)
            }
            if (choices.isEmpty()) { publish(target, PromptState.OpenOnly(PromptFallback.Incomplete)); return }
            val view = PromptView(prompt.question, prompt.context, PromptChoices(choices.first(), choices.drop(1)))
            entry.offeredAt = clock()
            publish(target, PromptState.Offered(view))
            val offeredAt = entry.offeredAt
            entry.expiry = scope.launch {
                delay(60_000)
                synchronized(lock) {
                    if (records[target] === entry && entry.offeredAt == offeredAt && flow.value[target] is PromptState.Offered) {
                        invalidate(entry); publish(target, PromptState.OpenOnly(PromptFallback.Expired))
                    }
                }
            }
        }
    }

    private fun unavailable(target: PromptTarget, entry: Entry, readGeneration: Long, reason: PromptFallback) {
        synchronized(lock) {
            if (records[target] === entry && entry.readGeneration == readGeneration) { invalidate(entry); publish(target, PromptState.OpenOnly(reason)) }
        }
    }

    private fun invalidate(entry: Entry) { entry.actions.clear(); entry.active.set(false); entry.expiry?.cancel() }
    private fun publish(target: PromptTarget, state: PromptState) { flow.value = flow.value + (target to state) }
}
