package dev.mahali.hatch

import dev.mahali.hatch.design.Hatch
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.floatingEdge
import dev.mahali.hatch.design.floatingFill
import dev.mahali.hatch.engine.InputLoad
import dev.mahali.hatch.engine.INPUT_MAX_IN_FLIGHT
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.protocol.ConnectionSnapshot
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
internal fun NetworkDiagnostics(rex: Rex, pathState: MutableStateFlow<NetworkPathSnapshot>, modifier: Modifier = Modifier) {
    val state by rex.state.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val diags = state.hosts.associateWith { id ->
        val source = rex.diagnostics(id)?.state
        key(id, source) { remember(source) { source?.sample(500) }?.collectAsState(source?.value ?: ConnectionSnapshot())?.value ?: ConnectionSnapshot() }
    }
    val loads = state.hosts.associateWith { id ->
        val source = rex.inputLoad(id)
        key(id, source) { remember(source) { source?.sample(500) }?.collectAsState(source?.value ?: InputLoad())?.value ?: InputLoad() }
    }
    val network by pathState.collectAsState()
    val log = remember(state.hosts, diags, network.events) { diagnosticLog(state, diags, network) }
    var expanded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<HostId?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) { delay(1_000); now = System.currentTimeMillis() }
    } }
    LaunchedEffect(rex, state.hosts, network.networkHandle, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val started = SystemClock.elapsedRealtime()
                coroutineScope { state.hosts.forEach { id -> launch { rex.sync(id)?.probe() } } }
                delay((PROBE_MILLIS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
            }
        }
    }
    val recovered = diags.entries.filter { (_, d) -> d.recoveredAtMillis?.let { now - it in 0 until BACK_MILLIS } == true }
        .maxByOrNull { it.value.recoveredAtMillis ?: 0 }
    val ready = state.hosts.count { state.byHost[it]?.link == Link.Online && diags[it]?.readyMillis != null }
    val rtt = diags.values.firstNotNullOfOrNull { it.roundTrips.lastOrNull()?.millis }
    val dot = when {
        state.hosts.isNotEmpty() && ready == state.hosts.size -> chrome.online
        state.hosts.any { state.byHost[it]?.link is Link.Offline } -> chrome.danger
        else -> chrome.warning
    }
    val shape = if (expanded) HatchShapes.Card else HatchShapes.Capsule
    Column(
        modifier.then(if (expanded) Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = Hatch.space.m) else Modifier)
            .clip(shape).background(floatingFill()).floatingEdge(shape).animateContentSize(),
    ) {
        Row(
            Modifier.clickable(role = Role.Button) { expanded = !expanded }
                .semantics { contentDescription = if (expanded) "Collapse diagnostics" else "Open diagnostics" }
                .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)
                .then(if (expanded) Modifier.fillMaxWidth() else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.s),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (recovered != null) chrome.warning else dot))
            Text(recovered?.let { state.byHost[it.key]?.host?.name } ?: "$ready ready", style = HatchType.Subhead,
                fontWeight = FontWeight.SemiBold, color = chrome.text, maxLines = 1)
            Text(recovered?.let { "back after ${seconds(it.value.lastOutageMillis ?: 0)}" } ?: rtt?.let { "$it ms" }.orEmpty(),
                Modifier.weight(1f, fill = expanded), style = HatchType.Subhead, color = chrome.secondary, maxLines = 1)
            Icon(if (expanded) HatchIcons.ChevronUpDown else HatchIcons.ChevronDown, null, Modifier.size(16.dp), tint = chrome.secondary)
        }
        if (expanded) Column(Modifier.weight(1f, fill = false)
            .then(if (open == null) Modifier.heightIn(max = 480.dp) else Modifier).padding(horizontal = Hatch.space.m)) {
                Column {
                    Text(network.transport, style = HatchType.Footnote, color = chrome.text)
                    Text(listOf(network.details, "for ${seconds(SystemClock.elapsedRealtime() - network.sinceMillis)}").filter { it.isNotEmpty() }.joinToString(" · "), style = HatchType.Footnote, color = chrome.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.padding(top = Hatch.space.s).fillMaxWidth()
                    .semantics { contentDescription = "Host connection details" }.clip(HatchShapes.Small).background(chrome.card)) {
                for (id in state.hosts) {
                    val host = state.byHost[id] ?: continue
                    val d = diags[id] ?: ConnectionSnapshot()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = Hatch.space.s)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button,
                            onClickLabel = if (open == id) "Collapse details" else "Expand details",
                        ) {
                            open = if (open == id) null else id
                            if (open != null) keyboard?.hide()
                        }.semantics {
                            contentDescription = "${host.host.name} diagnostics"
                            stateDescription = if (open == id) "Expanded" else "Collapsed"
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.s)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (host.link == Link.Online && d.readyMillis != null) chrome.online else if (host.link is Link.Offline) chrome.danger else chrome.warning))
                            Column(Modifier.weight(1f)) {
                                Text(host.host.name, style = HatchType.Subhead, fontWeight = FontWeight.SemiBold, color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(d.connectMillis?.let { "connect $it ms" } ?: diagnosticState(host.link, d), style = HatchType.Footnote, color = chrome.secondary)
                            }
                            Text(d.roundTrips.lastOrNull()?.let { "${it.millis} ms" } ?: diagnosticState(host.link, d), style = HatchType.Subhead, color = chrome.text)
                            Icon(if (open == id) HatchIcons.ChevronUpDown else HatchIcons.ChevronDown, null,
                                Modifier.size(14.dp), tint = chrome.secondary)
                        }
                        d.probeFailure?.let { Text("RTT probe failed: $it", style = HatchType.Footnote, color = chrome.warning, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (open == id) {
                            Detail("State", "${diagnosticState(host.link, d)} for ${seconds(now - d.stateSinceMillis)} · attempt ${d.attempt}")
                            d.roundTrips.takeIf { it.isNotEmpty() }?.let { probes ->
                                Detail("Round trip", "min ${probes.minOf { it.millis }} ms · avg ${probes.map { it.millis }.average().toInt()} ms · ${probes.size} probes")
                                Detail("Last reply", diagnosticTime(probes.last().replyAtMillis))
                            }
                            d.connectMillis?.let { Detail("Connect", "$it ms") }
                            d.resumeMillis?.let { Detail("Resume", "$it ms") }
                            d.bootstrapMillis?.let { Detail("Bootstrap", "$it ms") }
                            d.attachMillis?.let { Detail("Attach", "$it ms") }
                            d.readyMillis?.let { Detail("Ready after", seconds(it)) }
                            d.resolveMillis?.let { Detail("Control DNS", "$it ms") }
                            d.port?.let { port ->
                                Detail("TCP port", "${port.port} ${if (port.reachable) "reachable" else "unreachable"}")
                                Detail("TCP check", "${diagnosticTime(port.checkedAtMillis)}${port.millis?.let { " · $it ms" }.orEmpty()}${port.failure?.let { " · $it" }.orEmpty()}")
                                port.address?.let { Detail("TCP address", it) }
                                port.resolveMillis?.let { Detail("TCP DNS", "$it ms") }
                            }
                            Detail("Resumes", "${d.controlResumes} control, ${d.blockResumes} terminal")
                            Detail("Outage", d.lastOutageMillis?.let { seconds(it) } ?: "none recorded")
                            Detail("Endpoint", listOfNotNull(host.host.url, d.endpoint).joinToString(" · "))
                            Detail("Sessions", "${d.attachedSessions}/${host.sessions.size} attached · ${d.loadingSessions} loading")
                            Detail("Content", "${d.contentBlocks.size} visible panes · ${d.liveStreams.size} live streams · ${d.waitingStreams.size} waiting")
                            loads[id]?.let { Detail("Input", "${it.inFlight}/$INPUT_MAX_IN_FLIGHT in flight · ${it.batches} retained batches · ${it.bytes} bytes") }
                            d.lastFailure?.let { Detail("Failure", it) }
                            d.lastFailureAtMillis?.let { Detail("Last failure", diagnosticTime(it)) }
                        }
                    }
                }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    Modifier.weight(1f, fill = false).heightIn(min = 48.dp, max = 160.dp).fillMaxWidth()
                        .semantics { contentDescription = "Diagnostic events" },
                    state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(Hatch.space.xs),
                ) {
                    if (log.isEmpty()) item { Text("No events recorded", style = HatchType.Footnote, color = chrome.secondary) }
                    items(log) { event -> Row {
                        Text(diagnosticTime(event.atMillis), Modifier.width(78.dp), style = Hatch.type.mono, color = chrome.secondary)
                        Text(event.text, style = Hatch.type.mono, color = chrome.text)
                    } }
                }
            Row(Modifier.padding(top = 10.dp, bottom = Hatch.space.m), horizontalArrangement = Arrangement.spacedBy(Hatch.space.s)) {
                val report = { networkReport(network, state, diags, loads, log) }
                LogButton("Share", Modifier.weight(1f)) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report()), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                LogButton("Copy", Modifier.weight(1f)) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Network diagnostics", report()))
                }
                LogButton("Clear", Modifier.weight(1f), danger = true) {
                    state.hosts.forEach { rex.diagnostics(it)?.clearLog() }
                    pathState.update { it.copy(events = emptyList()) }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.padding(top = Hatch.space.xxs)) {
        Text(label, Modifier.width(86.dp), style = HatchType.Footnote, color = chrome.secondary)
        Text(value, Modifier.weight(1f), style = HatchType.Footnote, color = chrome.text,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogButton(label: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    Box(modifier.clip(HatchShapes.Small).background(if (danger) chrome.danger.copy(alpha = 0.18f) else chrome.control)
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center,
    ) { Text(label, style = HatchType.Callout, color = if (danger) chrome.danger else chrome.text) }
}

private const val PROBE_MILLIS = 5_000L
private const val BACK_MILLIS = 2_500L
