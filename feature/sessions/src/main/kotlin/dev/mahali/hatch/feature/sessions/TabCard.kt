package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.design.StatusGlyph
import dev.mahali.hatch.design.LocalListMetrics

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import dev.mahali.hatch.design.AnimationStyle
import dev.mahali.hatch.design.Hatch
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.mahali.hatch.core.RexActions
import dev.mahali.hatch.design.HatchConfirmDialog
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenu
import dev.mahali.hatch.design.HatchMenuDivider
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HatchRenameDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.ProgramTile
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.RowChevron
import dev.mahali.hatch.design.chrome

/** The 48 dp header row centres its 20 sp line, so its label sits [Dimens.SectionGap] below the card. */
internal val CardGap: Dp @Composable get() = Dimens.SectionGap - (Dimens.TouchTarget - 20.dp) / 2

@Composable
internal fun TabCard(
    tabs: List<TabRowModel>,
    selected: WindowRef?,
    onOpenTab: (WindowRef) -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    actions: RexActions? = null,
    onMenuShown: (Boolean) -> Unit = {},
    /** The list this card sits in, under [listKey], scrolled when a dragged tab nears its edge. */
    list: LazyListState? = null,
    listKey: Any? = null,
    haptics: Boolean = false,
) {
    // A drop shows its order until Rex sends one; a refused move gives it up after a while, so Rex's order wins either way.
    var pending by remember { mutableStateOf<List<WindowRef>?>(null) }
    val refs = tabs.map { it.ref }
    LaunchedEffect(refs) { pending = null }
    LaunchedEffect(pending) { if (pending != null) { delay(PENDING_ORDER_MILLIS); pending = null } }
    val shown = pending?.let { order -> tabs.sortedBy { tab -> order.indexOf(tab.ref).takeIf { it >= 0 } ?: order.size } } ?: tabs
    val drag = if (actions == null) null else {
        val scope = rememberCoroutineScope()
        val view = LocalView.current
        remember(scope, view) { TabDrag(scope, view) }
    }
    if (drag != null && actions != null) {
        val order = shown.map { it.ref }
        val spec = Hatch.motion.spec<Float>()
        SideEffect {
            drag.rows = order
            drag.spec = spec
            drag.haptics = haptics
            drag.place = { from, to ->
                if (from != to && from in order.indices && to in order.indices) {
                    pending = order.moved(from, to)
                    actions.placeTab(order[from], order.beforeOnceMoved(from, to)?.window)
                }
            }
        }
        if (list != null) EdgeScroll(drag, list, listKey)
    }
    Column(modifier.padding(horizontal = LocalListMetrics.current.inset).fillMaxWidth()) {
        if (shown.isEmpty()) HatchListRow { Text("No tabs", style = LocalListMetrics.current.body, color = chrome.secondary) }
        shown.forEachIndexed { index, tab ->
            // Keyed by tab, so an open menu or dialog stays with its tab when others come and go.
            key(tab.ref) {
                TabRow(tab, tab.ref == selected, query, onOpenTab, actions, index, last = index == shown.lastIndex, onMenuShown, drag)
            }
        }
    }
}

/** While a tab is dragged near the list's top or bottom, scrolls the list, and the tab with it so it stays under the finger. */
@Composable
private fun EdgeScroll(drag: TabDrag, list: LazyListState, listKey: Any?) {
    val density = LocalDensity.current
    val zone = with(density) { Dimens.TouchTarget.toPx() }
    val speed = with(density) { EdgeScrollSpeed.toPx() }
    LaunchedEffect(drag.dragging) {
        if (!drag.dragging) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val info = list.layoutInfo
            val card = info.visibleItemsInfo.firstOrNull { it.key == listKey }
            if (card != null) {
                val finger = card.offset + drag.from * drag.rowHeight + drag.fingerY + drag.dragY
                val bottom = (info.viewportEndOffset - info.afterContentPadding).toFloat()
                val velocity = edgeScrollSpeed(finger, info.viewportStartOffset.toFloat(), bottom, zone, speed)
                if (velocity != 0f) {
                    val used = list.scrollBy(velocity * (now - last) / NANOS_PER_SECOND)
                    if (used != 0f) drag.dragTo(drag.dragY + used)
                }
            }
            last = now
        }
    }
}

/** One tab. Its parameters are all stable, so it recomposes only when its own row changes. */
@Composable
private fun TabRow(
    tab: TabRowModel,
    selected: Boolean,
    query: String,
    onOpenTab: (WindowRef) -> Unit,
    actions: RexActions?,
    index: Int,
    last: Boolean,
    onMenuShown: (Boolean) -> Unit,
    drag: TabDrag?,
) {
    var shown by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val show = { open: Boolean -> shown = open; onMenuShown(open) }
    val first = index == 0
    val lifted = drag != null && drag.held == tab.ref
    val motion = Hatch.motion
    // As a held pane: it sinks a little and dims.
    val sink by animateFloatAsState(
        if (lifted) 1f else 0f,
        if (motion.style == AnimationStyle.Off) snap() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "tab held",
    )
    val menu by rememberUpdatedState { show(true) }
    val reorder = if (drag == null) Modifier else Modifier
        .holdToReorder(tab.ref, onHold = { drag.hold(tab.ref, it) }, onMenu = { menu() }, onDrag = drag::dragTo, onEnd = drag::end)
        .semantics {
            onLongClick("Tab options") { menu(); true }
            customActions = listOfNotNull(
                CustomAccessibilityAction("Move Up") { drag.place(index, index - 1); true }.takeIf { !first },
                CustomAccessibilityAction("Move Down") { drag.place(index, index + 1); true }.takeIf { !last },
            )
        }
        .graphicsLayer {
            translationY = drag.offset(tab.ref)
            val s = 1f - LIFT_SCALE * sink
            scaleX = s
            scaleY = s
            alpha = 1f - LIFT_DIM * sink
        }
    Box(if (drag == null) Modifier else Modifier.zIndex(if (lifted) 1f else 0f).onSizeChanged { drag.rowHeight = it.height.toFloat() }) {
        HatchListRow(
            modifier = reorder,
            onClick = { onOpenTab(tab.ref) },
            // A held row takes the selected tone, so it reads as one piece over the rows it passes.
            selected = selected || lifted,
            leading = { ProgramTile(tab.program, LocalListMetrics.current.leading, stacked = tab.stacked) },
            trailing = {
                StatusGlyph(tab.mark, tab.progress, showDone = false, kind = tab.kind)
                if (!LocalListMetrics.current.quiet) RowChevron()
            },
        ) {
            val metrics = LocalListMetrics.current
            Text(rowTitle(tab.title, tab.detail, query), style = metrics.body, color = if (selected) chrome.text else chrome.text.copy(alpha = metrics.restingText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actions == null || drag == null) return@Box
        HatchMenu(shown, onDismiss = { show(false) }, alignEnd = false) {
            HatchMenuItem("Rename Tab", { show(false); renaming = true }, HatchIcons.Pencil)
            HatchMenuItem("Move Backward", { show(false); drag.place(index, index - 1) }, HatchIcons.ChevronLeft, enabled = !first)
            HatchMenuItem("Move Forward", { show(false); drag.place(index, index + 1) }, HatchIcons.ChevronRight, enabled = !last)
            HatchMenuDivider()
            HatchMenuItem("Close Tab", { show(false); confirmClose = true }, HatchIcons.Trash, color = chrome.accent)
        }
        if (renaming) HatchRenameDialog(
            "Rename Tab", tab.title, fieldDescription = "Tab name",
            onSave = { actions.renameTab(tab.ref, it); renaming = false }, onDismiss = { renaming = false },
        )
        // The open tab's terminal sees it go and moves on through the navigator, as for any close.
        if (confirmClose) HatchConfirmDialog(
            "Close Tab?", "Every pane in this tab closes, and the programs running in them end.", "Close Tab",
            onConfirm = { actions.closeTab(tab.ref) }, onDismiss = { confirmClose = false },
        )
    }
}

// The pane strip's lift (PaneHost), so a held tab feels as a held pane does.
private const val LIFT_SCALE = 0.04f
private const val LIFT_DIM = 0.25f
private const val PENDING_ORDER_MILLIS = 3_000L
private const val NANOS_PER_SECOND = 1_000_000_000f

/** Edge auto-scroll at full speed, per second. */
private val EdgeScrollSpeed = 900.dp

@Composable
private fun rowTitle(title: String, detail: String, query: String): AnnotatedString {
    val text = if (detail.isEmpty()) title else "$title $detail"
    val match = SpanStyle(color = chrome.accent, fontWeight = FontWeight.SemiBold)
    val dim = SpanStyle(color = if (LocalListMetrics.current.quiet) chrome.tertiary else chrome.secondary)
    return buildAnnotatedString {
        append(title)
        if (detail.isNotEmpty()) withStyle(dim) { append(" $detail") }
        matchRanges(text, query).forEach { addStyle(match, it.first, it.last + 1) }
    }
}
