package dev.mahali.hatch.feature.terminal

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.allBlocks
import dev.mahali.hatch.core.blocks
import dev.mahali.hatch.core.rects
import dev.mahali.hatch.engine.TabOperations
import dev.mahali.hatch.protocol.RexError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Main thread only. [scope] lives as long as the screen.
internal class TabController(
    val target: WindowRef,
    private val wide: Boolean,
    state: StateFlow<AppState>,
    private val scope: CoroutineScope,
    private val operations: TabOperations,
    private val say: (String) -> Unit,
    private val onOpenTab: (WindowRef) -> Unit,
    private val onBack: () -> Unit,
    private val onSplit: (BlockId) -> Unit,
    // Navigation owns the destination; this reports only the source and its last position.
    private val onTabGone: (WindowRef, Int) -> Unit,
    private val onNewTab: () -> Unit,
) {
    // Only this tab's slice of the host, so another session's changes don't recompose this terminal.
    var host by mutableStateOf(state.value.byHost[target.host]?.slice(target))
        private set
    val session get() = host?.sessionsById?.get(target.session)
    // Null once the host no longer has the tab or its session.
    val window get() = host?.let { h ->
        h.windowsById[target.window]?.takeIf { h.link != Link.Online || target.session in h.sessionsById }
    }
    val hostOnline get() = host?.link == Link.Online

    var area by mutableStateOf(DpSize.Zero)
    var view by mutableStateOf(PaneView.Auto)
    var columnWidths by mutableStateOf<Map<String, Float>>(emptyMap())
    private var returnView = PaneView.Fit

    // Tiles only when every tile has room; otherwise pages like a phone.
    val tiled by derivedStateOf {
        val layout = window?.layout
        layout != null && when (view) {
            PaneView.Auto -> wide && tiles(layout.rects().values, area.width.value, area.height.value)
            PaneView.Focus -> false
            PaneView.Fit, PaneView.Scroll -> true
        }
    }

    val panes by derivedStateOf { window?.let { if (tiled) it.layout?.blocks().orEmpty() else it.allBlocks() }.orEmpty() }

    private var selection by mutableStateOf(PaneSelection())
    private fun reconciled() = selection.reconcile(panes, window?.focused)
    fun reconcileSelection() { selection = reconciled() }
    val current get() = reconciled().current
    var chosen: BlockId?
        get() = reconciled().local
        set(block) { if (block != null) selection = reconciled().choose(block) }
    var pending: BlockId?
        get() = reconciled().pending?.block
        set(block) { selection = if (block == null) reconciled().copy(pending = null) else reconciled().request(block) }
    val transition get() = reconciled().pending
    val hasLastPane get() = reconciled().last() != null
    fun lastPane() { reconciled().last()?.let { selection = it } }
    fun pagerBoundary(): PagerBoundary = reconciled().boundary()
    fun beginSwipe(): PagerBoundary {
        val next = reconciled()
        selection = next.copy(pending = null, version = next.version + 1)
        return selection.boundary()
    }
    fun swiped(boundary: PagerBoundary, index: Int) { selection = reconciled().swipe(boundary, index) }
    fun complete(request: PaneTransition) { selection = reconciled().complete(request) }
    fun activate(request: PaneTransition) {
        val next = reconciled()
        if (next.pending == request && request.block in next.panes) selection = next.copy(local = request.block)
    }

    var parked by mutableStateOf(false)
        private set

    val mode: LayoutMode get() = when {
        !tiled -> Pages(window?.allBlocks())
        view == PaneView.Scroll -> Columns
        else -> Tiles
    }

    private var left = false
    private var reportedGone = false
    private var seen = window != null
    private var wasGone = window == null
    // Kept so the neighbour shown on close is the adjacent tab, even after the server removes it.
    private var lastTabIndex = session?.windows?.indexOf(target.window)?.coerceAtLeast(0) ?: 0
    private var parking: Job? = null

    init {
        require(operations.target == target) { "Tab operations must belong to the displayed tab" }
        val slice = state.map { it.byHost[target.host]?.slice(target) }.distinctUntilChanged()
        scope.launch { slice.collect(::onHost) }
        // A tab already gone on arrival (old notification): wait for its session to load before deciding.
        scope.launch {
            slice.map { host ->
                val online = host?.link == Link.Online
                val gone = host?.windowsById?.get(target.window) == null || host.sessionsById[target.session] == null
                val revision = host?.sessionsById?.get(target.session)?.revision
                Triple(online, gone, revision)
            }
                .distinctUntilChanged()
                .collectLatest { (online, gone, _) ->
                    if (!online || !gone || seen) return@collectLatest
                    val missing = try {
                        operations.refresh() == false
                    } catch (e: RexError) {
                        e.code in MISSING
                    }
                    if (missing) tabGone()
                }
        }
    }

    private fun onHost(next: HostState?) {
        host = next
        selection = reconciled()
        session?.windows?.indexOf(target.window)?.takeIf { it >= 0 }?.let { lastTabIndex = it }
        val gone = window == null
        if (!gone) seen = true else if (seen && !wasGone) tabGone()
        wasGone = gone
    }

    // Leaves once: a second back during the exit animation would leave the list too.
    fun leave() {
        if (left) return
        left = true
        onBack()
    }

    // An optimistic close and the later server removal are one disappearance.
    fun tabGone() {
        if (reportedGone) return
        reportedGone = true
        onTabGone(target, lastTabIndex)
    }

    fun foreground(foreground: Boolean) {
        parking?.cancel()
        if (foreground) parked = false
        else parking = scope.launch {
            delay(BACKGROUND_GRACE_MILLIS)
            parked = true
        }
    }

    // Set on lift or pill tap, not when the slide stops, so it slides in at this device's size.
    // Nothing is set mid-drag: a released swipe must not resize the other device's panes.
    fun settled(paging: Boolean, dragging: Boolean, targetPage: Int, boundary: PagerBoundary? = null): BlockId? = when {
        tiled || !paging -> current
        dragging -> null
        transition?.block?.let { it in panes } == true -> transition?.block
        boundary != null && boundary != pagerBoundary() -> current
        boundary != null -> boundary.panes.getOrNull(targetPage) ?: current
        else -> panes.getOrNull(targetPage) ?: current
    }

    // Panes this device claims and sizes; none while parked.
    fun claims(settled: BlockId?, visible: () -> List<BlockId>): List<BlockId> = when {
        parked -> emptyList()
        !tiled -> listOfNotNull(settled)
        view == PaneView.Scroll -> visible()
        else -> panes
    }

    // Tiles stay tiles after an action, so a user's layout doesn't drop to pages when space is tight.
    private fun keepTiles() {
        if (tiled && view == PaneView.Auto) view = PaneView.Fit
    }

    fun split(anchor: BlockId, below: Boolean) {
        keepTiles()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            command("Could not split pane") {
                operations.split(anchor, below)?.let {
                    pending = it
                    onSplit(it)
                }
            }
        }
    }

    // Splits along the tile's longer side, so tiles stay wide enough for a 2x2 grid in two splits.
    fun splitCurrent() {
        val anchor = current ?: return
        val tile = if (tiled) window?.layout?.rects()?.get(anchor) else null
        split(anchor, below = tile != null && tile.height * area.height.value > tile.width * area.width.value)
    }

    fun close(block: BlockId) {
        val last = (window?.allBlocks()?.size ?: 0) <= 1
        // Close is optimistic: navigation does not wait for the network. A failed request is reported, never retried.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            command("Could not close pane") { operations.close(block) }
        }
        if (last) tabGone()
    }

    fun action(block: BlockId, action: PaneAction) {
        chosen = block
        when (action) {
            PaneAction.SplitRight -> split(block, false)
            PaneAction.SplitBelow -> split(block, true)
            PaneAction.Narrower, PaneAction.Wider -> {
                val saved = columnOf(block)?.blocks()?.firstNotNullOfOrNull { columnWidths[it.value] } ?: 0.5f
                val actual = if (area.width.value > 0f) columnWidth(area.width.value, saved) / area.width.value else saved
                setColumnWidth(block, nextColumnWidth(actual, action == PaneAction.Wider))
            }
            PaneAction.Close -> close(block)
            PaneAction.Zoom -> if (tiled) {
                returnView = if (view == PaneView.Scroll) PaneView.Scroll else PaneView.Fit
                view = PaneView.Focus
                pending = block
            } else view = returnView
            PaneAction.Move -> {
                view = if (view == PaneView.Scroll || !wide) PaneView.Scroll else PaneView.Fit
                say("Hold a pane title, then drag it onto another pane")
            }
        }
    }

    fun resizeColumn(block: BlockId, delta: Float) {
        val width = area.width.value
        if (width <= 0f) return
        val fraction = columnOf(block)?.blocks()?.firstNotNullOfOrNull { columnWidths[it.value] } ?: 0.5f
        setColumnWidth(block, columnWidth(width, (columnWidth(width, fraction) + delta) / width) / width)
    }

    suspend fun resize(divider: PaneDivider, ratio: Float) {
        keepTiles()
        editLayout { operations.resize(divider.before, divider.after, divider.axis, ratio) }
    }

    fun drop(block: BlockId, onto: BlockId, zone: DropZone) {
        keepTiles()
        scope.launch {
            editLayout {
                val place = zone.placement()
                if (place == null) operations.swap(block, onto)
                else operations.move(block, onto, place.vertical, place.before)
            }
        }
    }

    fun newTab() = onNewTab()

    fun openTab(ref: WindowRef) {
        if (ref == target) return
        onOpenTab(ref)
        // Navigation is local and immediate; server focus is a separate, fallible request.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            command("Could not focus tab") { operations.focusOtherTab(ref) }
        }
    }

    private fun columnOf(block: BlockId) = window?.layout?.scrollColumns()?.firstOrNull { block in it.blocks() }

    private fun setColumnWidth(block: BlockId, fraction: Float) {
        val column = columnOf(block) ?: return
        val ids = panes.map { it.value }.toSet()
        columnWidths = HashMap(columnWidths.filterKeys { it in ids }).apply { column.blocks().forEach { put(it.value, fraction) } }
    }

    // Expected server failures are shown; cancellation and bugs propagate.
    private suspend fun command(failure: String, operation: suspend () -> Unit) {
        try {
            operation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RexError) {
            say("$failure: ${e.message ?: e.code}")
        }
    }

    private suspend fun editLayout(operation: suspend () -> Unit) {
        command("Could not update layout", operation = operation)
    }

    companion object {
        // Keeps the presentation across process death.
        fun saver(make: () -> TabController): Saver<TabController, Any> = Saver(
            save = { arrayListOf(it.view.name, it.returnView.name, HashMap(it.columnWidths)) },
            restore = { saved ->
                // SAFETY: this Saver only writes a three-element ArrayList in save above.
                val s = saved as List<*>
                make().apply {
                    view = PaneView.valueOf(s[0] as String)
                    returnView = PaneView.valueOf(s[1] as String)
                    @Suppress("UNCHECKED_CAST")
                    // SAFETY: save copies columnWidths, whose keys are String and values Float.
                    columnWidths = s[2] as Map<String, Float>
                }
            },
        )
    }
}

// Compares equal while the tab is unchanged, so unrelated host changes don't recompose.
private fun HostState.slice(tab: WindowRef): HostState {
    val window = windowsById[tab.window]
    val blocks = window?.allBlocks().orEmpty().toSet()
    return copy(
        sessionsById = sessionsById[tab.session]?.let { mapOf(tab.session to it) }.orEmpty(),
        windowsById = window?.let { mapOf(tab.window to it) }.orEmpty(),
        blocksById = blocksById.filterKeys { it in blocks },
    )
}

private val MISSING = setOf("not_found", "session_not_found", "window_not_found")
private const val BACKGROUND_GRACE_MILLIS = 5_000L
