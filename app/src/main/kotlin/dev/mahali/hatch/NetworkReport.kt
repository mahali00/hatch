package dev.mahali.hatch

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.engine.INPUT_MAX_IN_FLIGHT
import dev.mahali.hatch.engine.InputLoad
import dev.mahali.hatch.protocol.ConnectionSnapshot
import dev.mahali.hatch.protocol.DiagnosticEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class NetworkPathSnapshot(
    val description: String,
    val sinceMillis: Long,
    val networkHandle: Long? = null,
    val events: List<DiagnosticEvent> = emptyList(),
) {
    val transport: String get() = description.substringBefore(" · ")
    val details: String get() = description.substringAfter(" · ", "")
}

internal fun diagnosticLog(state: AppState, diags: Map<HostId, ConnectionSnapshot>, network: NetworkPathSnapshot): List<DiagnosticEvent> =
    (network.events + diags.flatMap { (id, d) ->
        d.events.map { it.copy(text = "${state.byHost[id]?.host?.name.orEmpty()} ${it.text}") }
    }).sortedByDescending { it.atMillis }.take(100)

internal fun connectionSummary(d: ConnectionSnapshot): String = listOfNotNull(
    d.roundTrips.lastOrNull()?.let { "rtt ${it.millis} ms" } ?: "rtt not measured",
    d.connectMillis?.let { "connect $it ms" },
    d.resumeMillis?.let { "resume $it ms" },
    d.bootstrapMillis?.let { "bootstrap $it ms" },
    d.attachMillis?.let { "attach $it ms" },
    d.port?.let { "TCP port ${it.port} ${if (it.reachable) "reachable" else "unreachable"}" } ?: "TCP port not checked",
).joinToString(" · ")

internal fun inputBudget(loads: Map<HostId, InputLoad>): String =
    "Input budget ${loads.values.sumOf { it.inFlight }} of ${loads.size * INPUT_MAX_IN_FLIGHT} calls in flight · ${loads.values.sumOf { it.batches }} batches retained"

internal fun streamSummary(diags: Map<HostId, ConnectionSnapshot>): String =
    "Terminal streams ${diags.values.sumOf { it.liveStreams.size }} live, ${diags.values.sumOf { it.waitingStreams.size }} waiting · ${diags.values.sumOf { it.pendingRequests }} control requests pending"

internal fun networkReport(network: NetworkPathSnapshot, state: AppState, diags: Map<HostId, ConnectionSnapshot>, loads: Map<HostId, InputLoad>, log: List<DiagnosticEvent>): String = buildString {
    appendLine("Path: ${network.transport}")
    if (network.details.isNotEmpty()) appendLine("  ${network.details}")
    appendLine(streamSummary(diags))
    appendLine(inputBudget(loads))
    for (id in state.hosts) {
        val host = state.byHost[id] ?: continue
        val d = diags[id] ?: ConnectionSnapshot()
        appendLine("${host.host.name}: ${diagnosticState(host.link, d)}")
        appendLine("  ${connectionSummary(d)}")
        d.lastFailure?.let { appendLine("  last failure${d.lastFailureAtMillis?.let { " ${diagnosticTime(it)}" }.orEmpty()}: $it") }
        d.probeFailure?.let { appendLine("  RTT probe failed: $it") }
        d.port?.let { port ->
            appendLine("  TCP checked ${diagnosticTime(port.checkedAtMillis)}${port.millis?.let { " in $it ms" }.orEmpty()}${port.failure?.let { ": $it" }.orEmpty()}")
            port.address?.let { appendLine("  TCP address $it") }
            port.resolveMillis?.let { appendLine("  TCP DNS lookup $it ms") }
        }
        appendLine("  attempt ${d.attempt} · ${d.attachedSessions}/${host.sessions.size} sessions attached, ${d.loadingSessions} loading · resumes ${d.controlResumes} control, ${d.blockResumes} terminal")
        appendLine("  endpoint ${host.host.url}${d.endpoint?.let { " · $it" }.orEmpty()}")
        d.resolveMillis?.let { appendLine("  Control DNS $it ms") }
        d.readyMillis?.let { appendLine("  ready after ${seconds(it)}") }
        d.lastOutageMillis?.let { appendLine("  last outage ${seconds(it)}") }
        d.roundTrips.takeIf { it.isNotEmpty() }?.let { probes ->
            appendLine("  RTT min ${probes.minOf { it.millis }} ms · avg ${probes.map { it.millis }.average().toInt()} ms · ${probes.size} probes · last reply ${diagnosticTime(probes.last().replyAtMillis)}")
        }
    }
    appendLine()
    log.asReversed().forEach { appendLine("${diagnosticTime(it.atMillis)}  ${it.text}") }
    appendLine()
    append("RTT measures a server.version reply. TCP checks only establish that the port accepts a connection. Probes run every 5 seconds while diagnostics is visible.")
}.trimEnd()

internal fun diagnosticState(link: Link, d: ConnectionSnapshot): String =
    if (link == Link.Online && d.readyMillis == null) "attaching" else linkWord(link)

internal fun linkWord(link: Link): String = when (link) {
    Link.Online -> "ready"
    is Link.Connecting -> "connecting"
    is Link.Offline -> "offline"
}

internal fun seconds(millis: Long): String = String.format(Locale.US, "%.1fs", millis.coerceAtLeast(0) / 1000.0)
internal fun diagnosticTime(atMillis: Long): String = DiagnosticClock.format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))
private val DiagnosticClock = DateTimeFormatter.ofPattern("HH:mm:ss.S", Locale.US)
