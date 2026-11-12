package dev.mahali.hatch

import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.HostProbe
import dev.mahali.hatch.core.HostStore
import dev.mahali.hatch.core.ProbeResult
import dev.mahali.hatch.core.RexActions
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.protocol.ClientInfo
import dev.mahali.hatch.protocol.ControlStatus
import dev.mahali.hatch.protocol.RexApi
import dev.mahali.hatch.protocol.RexControl
import dev.mahali.hatch.protocol.RexError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AppActions internal constructor(
    private val state: StateFlow<AppState>,
    private val api: (HostId) -> RexApi?,
    private val reconnect: (HostId) -> Unit,
    private val hosts: HostStore,
    private val scope: CoroutineScope,
) : RexActions {
    constructor(rex: Rex, hosts: HostStore, scope: CoroutineScope) : this(
        rex.state, rex::api, { rex.reconnect(it) }, hosts, scope,
    )
    private val failureFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val failures: SharedFlow<String> get() = failureFlow

    /** A tab this device just made, for the app to open, as Rex opens a tab where it was created. */
    private val openFlow = MutableSharedFlow<WindowRef>(extraBufferCapacity = 4)
    val opens: SharedFlow<WindowRef> get() = openFlow

    private fun run(host: HostId, failure: String, call: suspend RexApi.() -> Unit) {
        scope.launch {
            try {
                val connection = api(host) ?: throw RexError("disconnected", "Host is not connected")
                connection.call()
            } catch (e: RexError) {
                failureFlow.tryEmit("$failure: ${e.message ?: e.code}")
            }
        }
    }

    private fun startup(host: HostId) = hosts.hosts.value.firstOrNull { it.id == host }?.startupCommand.orEmpty()

    override fun newSession(host: HostId) = run(host, "Session Creation Failed") {
        val session = createSession(startup(host))
        val window = withTimeoutOrNull(OPEN_WAIT_MILLIS) {
            state.first { it.byHost[host]?.sessionsById?.get(session)?.windows?.isNotEmpty() == true }
                .byHost[host]?.sessionsById?.get(session)?.windows?.first()
        }
        if (window != null) openFlow.tryEmit(WindowRef(host, session, window))
    }
    override fun newTab(host: HostId, session: SessionId) = run(host, "Couldn't Create Tab") {
        openFlow.tryEmit(WindowRef(host, session, newWindow(session, startup(host))))
    }
    override fun renameSession(host: HostId, session: SessionId, label: String) = run(host, "Couldn't Rename Session") { setSessionLabel(session, label) }
    override fun closeSession(host: HostId, session: SessionId) = run(host, "Couldn't Close Session") { destroySession(session) }
    override fun renameTab(ref: WindowRef, label: String) = run(ref.host, "Couldn't Rename Tab") { setWindowLabel(ref.session, ref.window, label) }
    override fun closeTab(ref: WindowRef) = run(ref.host, "Couldn't Close Tab") { closeWindow(ref.session, ref.window) }

    override fun moveTab(ref: WindowRef, forward: Boolean) {
        val windows = state.value.byHost[ref.host]?.sessionsById?.get(ref.session)?.windows ?: return
        val at = windows.indexOf(ref.window).takeIf { it >= 0 } ?: return
        // move_window places the tab just before another one, or last when given none.
        val before = if (forward) {
            if (at == windows.lastIndex) return
            windows.getOrNull(at + 2)
        } else {
            if (at == 0) return
            windows[at - 1]
        }
        placeTab(ref, before)
    }

    override fun placeTab(ref: WindowRef, before: WindowId?) = run(ref.host, "Couldn't Move Tab") { moveWindow(ref.session, ref.window, before) }

    override fun reconnect(host: HostId) { reconnect.invoke(host) }

    override fun setClientLabel(label: String) {
        state.value.hosts.forEach { host -> run(host, "Couldn't Set Label") { setClientLabel(label) } }
    }
}

class ControlProbe(private val rex: Rex, private val client: () -> ClientInfo, private val parent: CoroutineScope) : HostProbe {
    override suspend fun probe(url: String): ProbeResult {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
        val control = RexControl(rex.http, url, client, scope)
        return try {
            control.start()
            val outcome = withTimeoutOrNull(12_000) {
                control.status.first { it is ControlStatus.Open || (it is ControlStatus.Connecting && it.lastError != null) }
            }
            when (outcome) {
                is ControlStatus.Open -> ProbeResult.Reached(url, null)
                is ControlStatus.Connecting -> ProbeResult.Failed(outcome.lastError ?: "Couldn't connect")
                else -> ProbeResult.Failed("The server did not answer in time.")
            }
        } finally {
            control.stop()
            scope.cancel()
        }
    }
}

private const val OPEN_WAIT_MILLIS = 5_000L
