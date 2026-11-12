package dev.mahali.hatch.protocol

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

public data class DiagnosticEvent(val atMillis: Long, val text: String)
public data class RoundTrip(val millis: Long, val replyAtMillis: Long)

public data class ConnectionSnapshot(
    val stateSinceMillis: Long = System.currentTimeMillis(),
    val attempt: Int = 0,
    val connection: Int = 0,
    val connectMillis: Long? = null,
    val resumeMillis: Long? = null,
    val bootstrapMillis: Long? = null,
    val attachMillis: Long? = null,
    val readyMillis: Long? = null,
    val resolveMillis: Long? = null,
    val endpoint: String? = null,
    val controlResumes: Int = 0,
    val blockResumes: Int = 0,
    val attachedSessions: Int = 0,
    val loadingSessions: Int = 0,
    val pendingRequests: Int = 0,
    val contentBlocks: Set<String> = emptySet(),
    val waitingStreams: Set<String> = emptySet(),
    val liveStreams: Set<String> = emptySet(),
    val port: PortReachability? = null,
    val probeFailure: String? = null,
    val lastFailureAtMillis: Long? = null,
    val lastOutageMillis: Long? = null,
    val recoveredAtMillis: Long? = null,
    val lastFailure: String? = null,
    val roundTrips: List<RoundTrip> = emptyList(),
    val events: List<DiagnosticEvent> = emptyList(),
)

/** Updated at transport boundaries, whether or not diagnostics is shown. */
public class ConnectionDiagnostics(
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val flow = MutableStateFlow(ConnectionSnapshot(stateSinceMillis = clock()))
    // Every RPC bumps this twice; a bare counter avoids copying the snapshot each time.
    private val pending = MutableStateFlow(0)
    public val state: StateFlow<ConnectionSnapshot> = object : StateFlow<ConnectionSnapshot> {
        override val value: ConnectionSnapshot get() = flow.value.copy(pendingRequests = pending.value)
        override val replayCache: List<ConnectionSnapshot> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<ConnectionSnapshot>): Nothing {
            combine(flow, pending) { snapshot, count -> snapshot.copy(pendingRequests = count) }.collect(collector)
            error("unreachable")
        }
    }
    private var connectStarted = 0L
    private var outageStarted: Long? = null
    private var probeConnection = -1

    @Synchronized public fun connecting(attempt: Int) {
        connectStarted = elapsed()
        flow.update { it.copy(attempt = attempt + 1, connection = it.connection + 1, stateSinceMillis = clock(), resolveMillis = null, endpoint = null, readyMillis = null) }
        event("connecting, attempt ${attempt + 1}")
    }

    @Synchronized public fun connected(resumed: Boolean) {
        val duration = elapsed() - connectStarted
        val outage = outageStarted?.let { elapsed() - it }
        outageStarted = null
        flow.update { it.copy(connectMillis = duration, resumeMillis = if (resumed) duration else it.resumeMillis,
            controlResumes = it.controlResumes + if (resumed) 1 else 0, stateSinceMillis = clock(),
            lastOutageMillis = outage ?: it.lastOutageMillis, recoveredAtMillis = if (outage != null) clock() else it.recoveredAtMillis) }
        event("connected in $duration ms")
        if (resumed) event("resumed in $duration ms")
    }

    @Synchronized public fun dropped(reason: String) {
        if (outageStarted == null) outageStarted = elapsed()
        flow.update { it.copy(lastFailure = reason, lastFailureAtMillis = clock(), stateSinceMillis = clock()) }
        event("attempt ${flow.value.attempt} failed ($reason)")
    }

    public fun resolved(millis: Long, addresses: String) {
        flow.update { it.copy(resolveMillis = millis, endpoint = addresses) }
        event("DNS resolved in $millis ms: $addresses")
    }
    public fun endpoint(address: String) { flow.update { it.copy(endpoint = address) } }
    public fun bootstrap(millis: Long) { flow.update { it.copy(bootstrapMillis = millis, stateSinceMillis = clock()) }; event("bootstrap completed in $millis ms") }
    public fun attached(millis: Long) { flow.update { it.copy(attachMillis = millis) } }
    public fun sessions(attached: Int, loading: Int) { flow.update { it.copy(attachedSessions = attached, loadingSessions = loading) } }
    public fun requestStarted() { pending.update { it + 1 } }
    public fun requestFinished() { pending.update { it - 1 } }
    public fun content(block: String, visible: Boolean) { flow.update { it.copy(contentBlocks = if (visible) it.contentBlocks + block else it.contentBlocks - block) } }
    @Synchronized public fun streamWaiting(id: String) {
        val alreadyWaiting = id in flow.value.waitingStreams
        flow.update { it.copy(waitingStreams = it.waitingStreams + id, liveStreams = it.liveStreams - id) }
        if (!alreadyWaiting) event("terminal ${id.takeLast(6)} connecting")
    }
    public fun streamReady(id: String, resumed: Boolean) {
        flow.update { it.copy(waitingStreams = it.waitingStreams - id, liveStreams = it.liveStreams + id, blockResumes = it.blockResumes + if (resumed) 1 else 0) }
        event("terminal ${id.takeLast(6)} ${if (resumed) "resumed" else "ready"}")
    }
    public fun streamStopped(id: String) { flow.update { it.copy(waitingStreams = it.waitingStreams - id, liveStreams = it.liveStreams - id) } }
    public fun failure(text: String) { flow.update { it.copy(lastFailure = text, lastFailureAtMillis = clock()) }; event(text) }
    @Synchronized public fun probe(millis: Long) {
        val announce = probeConnection != flow.value.connection || flow.value.probeFailure != null
        probeConnection = flow.value.connection
        flow.update { it.copy(roundTrips = (it.roundTrips + RoundTrip(millis, clock())).takeLast(30), probeFailure = null) }
        if (announce) event("probe answered in $millis ms")
    }
    public fun probeFailed(reason: String) { flow.update { it.copy(probeFailure = reason) } }
    public fun reachability(result: PortReachability) {
        val previous = flow.value.port
        flow.update { it.copy(port = result) }
        if (previous?.reachable != result.reachable || previous.port != result.port) {
            event("port ${result.port} ${if (result.reachable) "reachable in ${result.millis} ms" else "unreachable (${result.failure})"}")
        }
    }
    @Synchronized public fun ready() {
        val duration = elapsed() - connectStarted
        flow.update { it.copy(readyMillis = duration, stateSinceMillis = clock()) }
        event("ready after ${String.format(java.util.Locale.US, "%.1fs", duration / 1000.0)}")
    }
    public fun event(text: String) { flow.update { it.copy(events = (listOf(DiagnosticEvent(clock(), text)) + it.events).take(100)) } }
    public fun clearLog() { flow.update { it.copy(events = emptyList()) } }
}
