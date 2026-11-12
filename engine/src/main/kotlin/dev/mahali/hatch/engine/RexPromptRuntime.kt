package dev.mahali.hatch.engine

import dev.mahali.hatch.core.AnswerOutcome
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.PromptFallback
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.promptTarget
import dev.mahali.hatch.core.existsIn
import dev.mahali.hatch.protocol.KeyEvent
import dev.mahali.hatch.protocol.promptScreen
import dev.mahali.hatch.protocol.foregroundProcess
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@OptIn(ExperimentalCoroutinesApi::class)
internal class RexPromptRuntime(private val rex: Rex) : PromptRuntime {
    private val decoders = Semaphore(1)
    override val state: StateFlow<AppState> get() = rex.state
    override val hints: Flow<PromptTarget> = rex.state.map { app -> app.hosts.mapNotNull { id -> rex.sync(id)?.let { id to it } } }
        .distinctUntilChanged().flatMapLatest { hosts ->
            merge(*hosts.flatMap { (host, sync) -> listOf(
                sync.blockSignals.mapNotNull { signal ->
                    if (signal.name !in setOf("bell", "desktop_notification")) null
                    else rex.state.value.promptTarget(host, signal.session, signal.block)
                },
                sync.connectionGeneration.transform {
                    val current = sync.state.value
                    for (session in current.sessions) for (block in current.blocksById.values) {
                        if (block.status.values.any { it.state == ProgramState.Blocked }) {
                            rex.state.value.promptTarget(host, session, block.id)?.let { emit(it) }
                        }
                    }
                },
            ) }.toTypedArray())
        }

    override fun source(target: PromptTarget): PromptSource? {
        val window = target.window
        val host = rex.state.value.byHost[window.host] ?: return null
        val serverId = host.serverId ?: return null
        val sync = rex.sync(window.host) ?: return null
        val input = rex.promptInput(window.host) ?: return null
        if (host.link != Link.Online || host.replacedServer || host.serverId.isNullOrBlank() ||
            !target.existsIn(rex.state.value)) return null
        return PromptSource(sync, sync.connectionGeneration.value, serverId, input.revision(target.block))
    }

    override suspend fun read(target: PromptTarget): PromptObservation = withTimeout(2_000) {
        val before = source(target) ?: error("Pane unavailable")
        val api = rex.api(target.window.host) ?: error("Host unavailable")
        val process = api.foregroundProcess(target.window.session, target.block)
        check(source(target) == before) { "Source changed" }
        if (process.promptAgent() == null) return@withTimeout PromptObservation(before, process, null)
        val export = api.promptScreen(target.window.session, target.block)
        check(source(target) == before) { "Source changed" }
        val agent = export.identity.foreground.promptAgent()
        val parsed = if (agent == null) null else decoders.withPermit { recognizePrompt(decodePromptScreen(export), agent) }
        PromptObservation(before, export.identity.foreground, parsed)
    }

    override suspend fun answer(
        target: PromptTarget,
        observation: PromptObservation,
        number: Int,
        valid: () -> Boolean,
        sending: () -> Unit,
    ): AnswerOutcome {
        val input = rex.promptInput(target.window.host) ?: return AnswerOutcome.NotSent(PromptFallback.Offline)
        val dispatched = AtomicBoolean(false)
        val recovered = AtomicBoolean(false)
        fun uncertain(): AnswerOutcome {
            if (recovered.compareAndSet(false, true)) rex.reconnect(target.window.host)
            return AnswerOutcome.DeliveryUnconfirmed
        }
        val queued = input.exclusive { permit ->
            val fresh = read(target)
            val prompt = fresh.prompt
            if (!valid() || fresh.source != observation.source || fresh.process != observation.process ||
                prompt == null || prompt.fingerprint != observation.prompt?.fingerprint || prompt.choices.none { it.number == number }) {
                return@exclusive AnswerOutcome.NotSent(PromptFallback.Changed)
            }
            val api = rex.api(target.window.host) ?: return@exclusive AnswerOutcome.NotSent(PromptFallback.Offline)
            if (!permit.claim(target.block, observation.source.revision) { if (valid()) dispatched.set(true) }) {
                return@exclusive AnswerOutcome.NotSent(PromptFallback.Changed)
            }
            if (!dispatched.get()) return@exclusive AnswerOutcome.NotSent(PromptFallback.Changed)
            try {
                sending()
                val choice = prompt.choices.first { it.number == number }
                for (action in listOf("press", "release")) {
                    api.key(target.window.session, target.block, KeyEvent(choice.key, text = choice.text, action = action))
                }
                AnswerOutcome.InputAcknowledged
            } catch (error: CancellationException) {
                uncertain()
                throw error
            } catch (error: Exception) {
                // Tear down the uncertain transport while exclusivity is held, before later input leaves the queue.
                uncertain()
            }
        }
        try {
            return when (val result = withTimeout(6_000) { queued.await() }) {
                InputReservation.Rejected -> AnswerOutcome.NotSent(PromptFallback.InputBusy)
                is InputReservation.Completed -> result.value
                is InputReservation.Failed -> if (dispatched.get()) uncertain() else AnswerOutcome.NotSent(PromptFallback.Changed)
            }
        } catch (error: TimeoutCancellationException) {
            queued.cancel()
            return if (dispatched.get()) uncertain() else AnswerOutcome.NotSent(PromptFallback.InputBusy)
        } catch (error: CancellationException) {
            queued.cancel()
            if (dispatched.get()) uncertain()
            throw error
        }
    }

}
