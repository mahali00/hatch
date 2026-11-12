package dev.mahali.hatch.engine

import android.os.Trace
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.DeviceClass
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.promptTarget
import dev.mahali.hatch.protocol.ClientInfo
import dev.mahali.hatch.protocol.DataStream
import dev.mahali.hatch.protocol.RemoteFile
import dev.mahali.hatch.protocol.HostSync
import dev.mahali.hatch.protocol.KeyEvent
import dev.mahali.hatch.protocol.RexApi
import dev.mahali.hatch.protocol.RexControl
import dev.mahali.hatch.protocol.UploadSource
import dev.mahali.hatch.protocol.Uploader
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

public typealias GridSize = dev.mahali.hatch.core.GridSize
public data class PaneRequest(public val target: PromptTarget, public val serial: Long)

@OptIn(ExperimentalCoroutinesApi::class)
public class Rex(
    private val scope: CoroutineScope,
    private val clientInfo: () -> ClientInfo,
    private val log: (String) -> Unit = {},
    private val frameCache: FrameCache? = null,
    private val stateCache: StateCache? = null,
    deviceClass: DeviceClass = DeviceClass.Normal,
    private val previewOwner: UUID = UUID.randomUUID(),
) {
    public val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) // Server pings every 120 s; ping sooner to notice a dead server.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .dns(StickyDns())
        .build()

    private class Connection(
        val id: Long,
        val host: Host,
        val sync: HostSync,
        val scope: CoroutineScope,
        val input: OrderedInput,
        val channel: HostChannel,
    )

    private val inputFailureFlow = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    public val inputFailures: SharedFlow<String> get() = inputFailureFlow

    private val connections = MutableStateFlow<List<Connection>>(emptyList())
    private val connectionIds = AtomicLong()

    private val panes = PaneRegistry(
        CoroutineScope(scope.coroutineContext + Dispatchers.Main),
        frameCache,
        maxWarm = if (deviceClass == DeviceClass.Low) LOW_END_MAX_WARM else MAX_WARM,
    )

    public val state: StateFlow<AppState> = connections.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(AppState())
        else combine(list.map { it.sync.state }) { states ->
            AppState(list.map { it.host.id }, states.associateBy { it.host.id })
        }
    }.stateIn(scope, SharingStarted.Eagerly, AppState())

    /** Created on first use; observation never opens a terminal data stream. */
    public val promptActions: PromptActions by lazy { PromptActions(RexPromptRuntime(this), scope, initiallyEnabled = false) }

    private val focusedPaneFlow = MutableStateFlow<PromptTarget?>(null)
    public val focusedPane: StateFlow<PromptTarget?> get() = focusedPaneFlow
    private val requestedPaneFlow = MutableStateFlow<PaneRequest?>(null)
    private val paneRequestIds = AtomicLong()
    public val requestedPane: StateFlow<PaneRequest?> get() = requestedPaneFlow
    public fun requestPane(target: PromptTarget) {
        requestedPaneFlow.value = PaneRequest(target, paneRequestIds.incrementAndGet())
    }
    public fun consumePaneRequest(request: PaneRequest) {
        requestedPaneFlow.compareAndSet(request, null)
    }

    internal fun promptInput(host: HostId): OrderedInput? = connections.value.firstOrNull { it.host.id == host }?.input

    /** Paused hosts are left out: they never connect or retry, and drop out of [state]. */
    public suspend fun setHosts(saved: List<Host>): Unit = withContext(Dispatchers.Main.immediate) {
        val hosts = saved.filter { it.enabled }
        val current = connections.value.associateBy { it.host.id }
        val next = hosts.map { host ->
            val existing = current[host.id]
            when {
                existing == null -> connect(host)
                existing.host.url != host.url -> connect(host).also { disconnect(existing) }
                else -> {
                    if (existing.host != host) existing.sync.edit(host)
                    Connection(existing.id, host, existing.sync, existing.scope, existing.input, existing.channel)
                }
            }
        }
        current.values.filter { old -> hosts.none { it.id == old.host.id } }.forEach(::disconnect)
        connections.value = next
    }

    @OptIn(FlowPreview::class)
    private suspend fun connect(host: Host): Connection {
        val initial = withContext(Dispatchers.IO) { stateCache?.load(host) }
        val hostScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val control = RexControl(http, host.url, clientInfo, hostScope, log = { log("${host.name}: $it") })
        val sync = HostSync(host, control, hostScope, initial = initial)
        stateCache?.let { cache ->
            hostScope.launch(Dispatchers.IO) {
                sync.state.filter { it.link == Link.Online }.sample(2_000).collect { cache.save(it) }
            }
        }
        // HostChannel reports its own calls' failures; nothing else submits plain calls.
        val input = OrderedInput(hostScope, failure = { error -> log("${host.name}: input failed: ${error.message}") })
        val channel = HostChannel(
            host.name, input,
            online = { sync.state.value.link == Link.Online },
            perform = sync.api::calls,
            inputFailed = { inputFailureFlow.tryEmit(it) },
            log = { log(it) },
            scope = hostScope,
        )
        hostScope.launch {
            sync.state.map { it.link }.distinctUntilChanged().collect { link ->
                if (link == Link.Online) channel.retryCleanup()
            }
        }
        sync.start()
        return Connection(connectionIds.incrementAndGet(), host, sync, hostScope, input, channel)
    }

    private fun disconnect(connection: Connection) {
        panes.closeHost(connection.host.id)
        keyboardOpen.removeAll { it.host == connection.host.id }
        connection.input.close()
        connection.sync.stop()
        connection.scope.cancel()
    }

    public fun sync(host: HostId): HostSync? = connections.value.firstOrNull { it.host.id == host }?.sync
    public fun api(host: HostId): RexApi? = sync(host)?.api
    public fun tabOperations(target: WindowRef): TabOperations = createTabOperations(
        target,
        api = ::api,
        sync = { sync(target.host) },
        startup = { state.value.byHost[target.host]?.host?.startupCommand.orEmpty() },
    )
    public fun diagnostics(host: HostId): dev.mahali.hatch.protocol.ConnectionDiagnostics? = sync(host)?.diagnostics
    public fun reconnect(host: HostId): Unit? = sync(host)?.reconnect()

    private val liveness = Liveness(scope)

    public fun nudge() {
        for (connection in connections.value) {
            val sync = connection.sync
            if (sync.state.value.link != Link.Online) sync.reconnect()
            else {
                val generation = sync.connectionGeneration.value
                // A reconnect meanwhile already replaced the link probed.
                liveness.check(connection.id, sync::alive) { if (sync.connectionGeneration.value == generation) sync.reconnect() }
            }
        }
        for (pane in panes.shown()) {
            if (pane.status.value.stream == DataStream.State.Reconnecting) pane.retry()
        }
    }

    public fun acquire(host: HostId, session: SessionId, block: BlockId): PaneAcquisition? {
        return panes.acquire(PaneKey(host, block), onWarm = { key ->
            diagnostics(key.host)?.content(key.block.value, false)
        }) {
            val connection = connections.value.firstOrNull { it.host.id == host } ?: return@acquire null
            Pane(session, block, { sink ->
                DataStream(http, connection.host.url, DataStream.TERMINAL, { connection.sync.api.terminalTicket(session, block) }, sink, connection.scope, log, connection.sync.diagnostics, block.value)
            }, panes.parsing, savedFrame = { frameCache?.load(host, block) }).also { it.start() }
        }.also { acquisition ->
            if (acquisition != null) diagnostics(host)?.content(block.value, true)
        }
    }

    private val keyboardOpen = mutableSetOf<PaneKey>()
    public fun keyboardWasOpen(host: HostId, block: BlockId): Boolean = PaneKey(host, block) in keyboardOpen
    public fun rememberKeyboard(host: HostId, block: BlockId, open: Boolean) {
        val key = PaneKey(host, block)
        if (open) keyboardOpen += key else keyboardOpen -= key
    }

    // Everything sent to a host goes through its channel, in order

    private fun send(host: HostId, command: Command): CommandSubmission {
        // An empty section marks the moment of input, for key-to-echo in scripts/trace.sh.
        if (command is Command.Input) { Trace.beginSection("Hatch.input"); Trace.endSection() }
        val channel = connections.value.firstOrNull { it.host.id == host }?.channel
        if (channel != null) return channel.send(command)
        if (command is Command.Input) inputFailureFlow.tryEmit("Input wasn't sent because the host is disconnected.")
        return CommandSubmission.Rejected(AdmissionFailure.Disconnected)
    }

    public fun inputLoad(host: HostId): StateFlow<InputLoad>? = connections.value.firstOrNull { it.host.id == host }?.input?.load

    public fun key(host: HostId, session: SessionId, block: BlockId, key: KeyEvent) {
        send(host, Command.Input.Key(session, block, key))
    }

    public fun text(host: HostId, session: SessionId, block: BlockId, text: String, mods: Mods = Mods()) {
        if (text.isNotEmpty()) send(host, Command.Input.Text(session, block, text, mods))
    }

    public fun paste(host: HostId, session: SessionId, block: BlockId, text: String) {
        send(host, Command.Input.Paste(session, block, text))
    }
    /** Close it with its tab. */
    public fun lease(host: HostId, session: SessionId, scope: CoroutineScope): PaneLease {
        val updates = connections.flatMapLatest { list ->
            val connection = list.firstOrNull { it.host.id == host }
            if (connection == null) flowOf(LeaseHost(null))
            else combine(
                connection.sync.state, connection.sync.connectionGeneration,
                connection.input.capacityVersion,
            ) { observed, generation, capacityVersion ->
                LeaseHost(observed, connection.id, generation, capacityVersion = capacityVersion)
            }
        }
        val calls = LeaseCalls(updates, submit = submit@{ expected, command ->
            val connection = connections.value.firstOrNull { it.host.id == host && it.id == expected }
                ?: return@submit CommandSubmission.Rejected(AdmissionFailure.Disconnected)
            when (command) {
                is Command.Control.Select -> focusedPaneFlow.value = state.value.promptTarget(host, command.session, command.block)
                is Command.Control.Focus -> {
                    if (command.focused) focusedPaneFlow.value = state.value.promptTarget(host, command.session, command.block)
                    else focusedPaneFlow.value?.takeIf { it.window.host == host && it.block == command.block }
                        ?.let { focusedPaneFlow.compareAndSet(it, null) }
                }
                else -> Unit
            }
            connection.channel.send(command)
        }, cleanup = { expected, commands ->
            connections.value.firstOrNull { it.host.id == host && it.id == expected }?.channel?.cleanup(commands)
        })
        return PaneLease(calls, session, scope)
    }

    public fun wheel(host: HostId, session: SessionId, block: BlockId, col: Int, row: Int, lines: Int) {
        if (lines != 0) send(host, Command.Input.Wheel(session, block, col, row, lines))
    }

    public fun click(host: HostId, session: SessionId, block: BlockId, col: Int, row: Int) {
        send(host, Command.Input.Click(session, block, col, row))
    }

    public suspend fun upload(host: HostId, session: SessionId, block: BlockId, files: List<UploadSource>, progress: (Long, Long) -> Unit) {
        val connection = connections.value.firstOrNull { it.host.id == host } ?: return
        Uploader(http, connection.host.url, connection.sync.api, log).upload(session, block, files, progress)
    }

    public suspend fun openRemoteFile(host: HostId, session: SessionId, path: String): RemoteFile? {
        val connection = connections.value.firstOrNull { it.host.id == host } ?: return null
        return RemoteFile.open(connection.sync.api, http, connection.host.url, session, path, connection.scope, previewOwner)
    }

    public suspend fun close(): Unit = withContext(Dispatchers.Main.immediate) {
        connections.value.forEach(::disconnect)
        panes.close()
        keyboardOpen.clear()
        connections.value = emptyList()
    }
}
