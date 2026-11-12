package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Effect
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostEvent
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.reduce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public data class BlockSignal(val session: SessionId, val block: BlockId, val name: String, val payload: JsonObject)

/**
 * Every change to the host state runs on one coroutine, in order. A result is re-applied over
 * any newer event (by `seq`), so a view never undoes a newer event.
 */
public class HostSync(
    host: Host,
    private val control: RexControl,
    private val scope: CoroutineScope,
    initial: HostState? = null,
) {
    public val api: RexApi = RexApi(control)
    public val diagnostics: ConnectionDiagnostics get() = control.diagnostics

    private val stateFlow = MutableStateFlow(initial ?: HostState(host))
    public val state: StateFlow<HostState> get() = stateFlow
    private val generationFlow = MutableStateFlow(0L)
    /** Changes on every connection, even when Rex resumes the same client ID. */
    public val connectionGeneration: StateFlow<Long> get() = generationFlow

    private val blockSignalFlow = MutableSharedFlow<BlockSignal>(extraBufferCapacity = 256)
    public val blockSignals: SharedFlow<BlockSignal> get() = blockSignalFlow

    private sealed interface Input {
        data class Signal(val signal: ControlSignal) : Input
        data class Result(
            val event: HostEvent,
            val seq: Long,
            val generation: Long,
            val applied: CompletableDeferred<Unit>? = null,
            val attached: SessionId? = null,
        ) : Input
        data class Plain(val event: HostEvent) : Input
        data class ViewDone(val session: SessionId, val attached: Boolean, val retry: Boolean, val generation: Long) : Input
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)

    // Touched only by the inbox loop: one owner, no locks.

    private val recent = ArrayDeque<Pair<Long, HostEvent>>()
    private val attached = mutableSetOf<SessionId>()
    private val loading = mutableSetOf<SessionId>()
    private val reload = mutableSetOf<SessionId>()
    private val detailsRequested = mutableSetOf<BlockId>()
    private var pendingReady = false
    private var bootstrapped = false

    init {
        // Bound before calls are accepted, so a reply carries the generation it was sent on.
        control.bindWelcome { generation -> generationFlow.value = generation }
        scope.launch { for (signal in control.signals) inbox.send(Input.Signal(signal)) }
        scope.launch {
            control.status.collect { status ->
                if (status is ControlStatus.Connecting) {
                    val link = if (status.lastError == null) Link.Connecting(status.retryAtMillis)
                    else Link.Offline(status.lastError, status.retryAtMillis)
                    inbox.send(Input.Plain(HostEvent.LinkChanged(link)))
                }
            }
        }
        scope.launch { for (input in inbox) handle(input) }
    }

    public fun start(): Unit = control.start()

    public fun edit(host: Host) {
        inbox.trySend(Input.Plain(HostEvent.HostEdited(host)))
    }
    public fun stop(): Unit = control.stop()
    public fun reconnect(): Unit = control.reconnectNow()
    public suspend fun probe(): Unit = control.probe()

    public suspend fun alive(): Boolean = try {
        control.call("server.version", timeoutMillis = 2_500); true
    } catch (e: RexError) { false }

    public suspend fun refresh(session: SessionId) {
        val started = System.nanoTime()
        val reply = control.call("session.attach", buildJsonObject { put("session_id", session.value) })
        diagnostics.attached((System.nanoTime() - started) / 1_000_000)
        val applied = CompletableDeferred<Unit>()
        inbox.send(Input.Result(HostEvent.ViewLoaded(decodeView(reply.payload.obj("view") ?: throw RexError("invalid_response", "Missing session view"))), reply.seq, reply.generation, applied, session))
        applied.await()
    }

    private fun handle(input: Input) {
        when (input) {
            is Input.Signal -> when (val s = input.signal) {
                is ControlSignal.Opened -> {
                    recent.clear()
                    pendingReady = true
                    bootstrapped = false
                    detailsRequested.clear()
                    if (!s.resumed) attached.clear()
                    loading.clear()
                    diagnostics.sessions(attached.size, loading.size)
                    scope.launch { bootstrap() }
                }
                is ControlSignal.Event -> {
                    decodeEvent(s.event)?.let { event ->
                        recent.addLast(s.seq to event)
                        if (recent.size > 512) recent.removeFirst()
                        apply(event)
                    }
                    blockSignal(s.event)?.let { blockSignalFlow.tryEmit(it) }
                }
                is ControlSignal.Dropped -> Unit
            }
            is Input.Result -> {
                // Stale reply: fail the waiter as disconnected, not as an empty view, so the tab is not declared gone.
                if (input.generation != generationFlow.value) {
                    input.applied?.completeExceptionally(RexError("disconnected", "Connection replaced"))
                } else {
                    if (input.event is HostEvent.Bootstrapped) bootstrapped = true
                    apply(input.event)
                    recent.filter { it.first > input.seq }.forEach { apply(it.second) }
                    input.attached?.takeIf { it in stateFlow.value.sessions }?.let { attached += it; diagnostics.sessions(attached.size, loading.size) }
                    input.applied?.complete(Unit)
                }
            }
            is Input.Plain -> apply(input.event)
            is Input.ViewDone -> {
                if (input.generation == generationFlow.value) {
                    loading -= input.session
                    if (input.attached && input.session in stateFlow.value.sessions) attached += input.session
                    if ((reload.remove(input.session) || input.retry) && input.session in stateFlow.value.sessions) loadView(input.session)
                    diagnostics.sessions(attached.size, loading.size)
                }
            }
        }
        if (pendingReady && bootstrapped && stateFlow.value.link == Link.Online && loading.isEmpty() && stateFlow.value.sessions.all { it in attached }) {
            pendingReady = false
            diagnostics.ready()
        }
    }

    private fun apply(event: HostEvent) {
        if (event is HostEvent.SessionDestroyed) { attached -= event.id; loading -= event.id; diagnostics.sessions(attached.size, loading.size) }
        val reduced = stateFlow.value.reduce(event)
        stateFlow.value = reduced.state
        reduced.effects.forEach { effect ->
            when (effect) {
                is Effect.LoadView -> loadView(effect.session)
                is Effect.LoadBlockDetails -> requestDetails(effect.session, effect.block)
            }
        }
        if (event is HostEvent.ViewLoaded) {
            event.view.windows.flatMap { it.blocks }.forEach { requestDetails(event.view.id, it.id) }
        }
    }

    private fun requestDetails(session: SessionId, block: BlockId) {
        if (detailsRequested.add(block)) scope.launch { loadDetails(session, block) }
    }

    private suspend fun bootstrap() {
        val started = System.nanoTime()
        try {
            val reply = control.call("session.bootstrap", buildJsonObject {
                put("event_scope", "all")
                put("attach_session_ids", JsonArray(emptyList()))
            })
            diagnostics.bootstrap((System.nanoTime() - started) / 1_000_000)
            val p = reply.payload
            val sessions = p.obj("attach_all")?.arr("sessions")?.objects().orEmpty()
                .mapNotNull { s -> s.str("session_id")?.let { SessionId(it) to s.str("label").orEmpty() } }
            val event = HostEvent.Bootstrapped(p.str("client_id").orEmpty(), p.obj("server_status")?.str("host_id"), sessions)
            inbox.send(Input.Result(event, reply.seq, reply.generation))
        } catch (e: RexError) {
            if (e.code != "disconnected") inbox.send(Input.Plain(HostEvent.LinkChanged(Link.Offline(e.message ?: e.code))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            inbox.send(Input.Plain(HostEvent.LinkChanged(Link.Offline(e.message ?: e.javaClass.simpleName))))
        }
    }

    private fun loadView(session: SessionId) {
        if (session in loading) { reload += session; return }
        loading += session
        diagnostics.sessions(attached.size, loading.size)
        val attach = session !in attached
        scope.launch {
            val generation = generationFlow.value
            var nowAttached = false
            var retry = false
            try {
                val started = System.nanoTime()
                val reply = if (attach) control.call("session.attach", buildJsonObject { put("session_id", session.value) })
                else control.call("session.view", buildJsonObject { put("session_id", session.value) })
                if (attach) diagnostics.attached((System.nanoTime() - started) / 1_000_000)
                nowAttached = attach
                val view = if (attach) reply.payload.obj("view") else reply.payload
                view?.let { inbox.send(Input.Result(HostEvent.ViewLoaded(decodeView(it)), reply.seq, reply.generation)) }
            } catch (e: RexError) {
                if (e.code in setOf("session_not_found", "not_found")) inbox.send(Input.Plain(HostEvent.SessionDestroyed(session)))
                else if (e.code == "timeout") {
                    diagnostics.failure("session $session view timed out; retrying")
                    delay(1_000)
                    retry = true
                } else if (e.code != "disconnected") diagnostics.failure("session $session view failed: ${e.message}")
            } finally {
                inbox.send(Input.ViewDone(session, nowAttached, retry, generation))
            }
        }
    }

    private suspend fun loadDetails(session: SessionId, block: BlockId) {
        suspend fun load(method: String, decode: (JsonObject) -> HostEvent) {
            try {
                val reply = api.terminalReply(session, block, method)
                inbox.send(Input.Result(decode(reply.payload), reply.seq, reply.generation))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RexError) {
                if (e.code != "disconnected") diagnostics.failure("block ${block.value} $method failed: ${e.message}")
            }
        }
        // Concurrent: four serial round trips would hold the socket keystrokes share for 4 RTTs per block.
        coroutineScope {
            launch { load("title") { HostEvent.TitleChanged(block, it.str("title").orEmpty()) } }
            launch { load("pwd") { HostEvent.PwdChanged(block, it.str("pwd").orEmpty()) } }
            launch { load("process") { HostEvent.ProcessChanged(block, it.obj("foreground")?.let(::processName).orEmpty()) } }
            launch { load("program_status") { HostEvent.StatusLoaded(block, it.arr("records").objects().mapNotNull(::decodeStatusRecord)) } }
        }
    }

    private fun blockSignal(event: JsonObject): BlockSignal? {
        if (event.str("type") != "session_event") return null
        val p = event.obj("payload") ?: return null
        val inner = p.obj("event")?.takeIf { it.str("type") == "block_event" }?.obj("payload") ?: return null
        val name = inner.str("name") ?: return null
        if (name !in signalNames) return null
        return BlockSignal(SessionId(p.str("session_id").orEmpty()), BlockId(inner.str("block_id").orEmpty()), name,
            inner.obj("payload") ?: JsonObject(emptyMap()))
    }

    private companion object {
        val signalNames = setOf(
            "bell", "clipboard_written", "desktop_notification", "program_status_changed", "program_status_removed",
            "selection_copied", "url_clicked", "child_exited",
        )
    }
}
