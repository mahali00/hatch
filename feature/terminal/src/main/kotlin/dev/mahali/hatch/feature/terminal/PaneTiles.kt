package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchPillText
import dev.mahali.hatch.design.HatchPill
import androidx.compose.ui.zIndex
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.Title
import dev.mahali.hatch.design.ProgramGlyph
import dev.mahali.hatch.design.chrome
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class TileStrip(val program: ProgramKey, val title: Title)

private class Drag(val block: BlockId, val at: Offset)
private data class Resize(val divider: PaneDivider, val ratio: Float, val dragging: Boolean = true)

// Drops mirror Rex Mac: onto an edge moves the pane beside the target, onto its centre swaps them.
// The terminal is rewrapped once, on release of a grip drag.
@Composable
internal fun PaneTiles(
    stage: TerminalViewport,
    scrollable: Boolean,
    gestures: CanvasGestures,
    strip: (BlockId) -> TileStrip,
    onSelect: (BlockId) -> Unit,
    onPickUp: () -> Unit,
    onDropTarget: () -> Unit,
    onDropped: () -> Unit,
    onAction: (BlockId, PaneAction) -> Unit,
    onResize: suspend (PaneDivider, Float) -> Unit,
    onColumnResize: (BlockId, Float) -> Unit,
    onDrop: (block: BlockId, target: BlockId, zone: DropZone) -> Unit,
    modifier: Modifier = Modifier,
    terminal: @Composable (PlacedPane) -> Unit,
) {
    var drag by remember { mutableStateOf<Drag?>(null) }
    var held by remember { mutableStateOf<BlockId?>(null) }
    var resize by remember { mutableStateOf<Resize?>(null) }
    var menu by remember { mutableStateOf<BlockId?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val handle = with(density) { 12.dp.toPx() }
    val showing by remember(stage) { derivedStateOf { stage.composed.value.filter { it.visible } } }
    val lines by remember(stage, handle) { derivedStateOf { visibleLines(stage, handle) } }
    // Mounts a couple of panes per frame, focused first, so twelve panes are several short frames, not one long one.
    var budget by remember { mutableIntStateOf(MOUNT_PER_FRAME) }
    LaunchedEffect(showing.size) { while (budget < showing.size) { withFrameNanos {}; budget += MOUNT_PER_FRAME } }
    val mounted = showing.sortedByDescending { it.focused }.take(budget).map { it.block }.toSet()

    // Grids hold while a divider is dragged; placement takes its ratio. Column widths change through onColumnResize.
    fun show(next: Resize?) {
        val was = resize?.dragging == true
        resize = next
        if (next?.dragging == true && !was) stage.beginDividerDrag()
        val ratio = next?.takeUnless { scrollable && it.divider.axis == Axis.Row }
        stage.preview(ratio?.divider?.path, ratio?.ratio ?: 0f)
        if (was && next?.dragging != true) stage.endDividerDrag()
    }
    fun commit() {
        val next = resize ?: return
        show(next.copy(dragging = false))
        scope.launch {
            try {
                onResize(next.divider, next.ratio)
                // The host answers before its new layout arrives; dropping the preview then would flash the old split.
                withTimeoutOrNull(LAYOUT_WAIT_MILLIS) {
                    snapshotFlow { stage.hostRatio(next.divider.path) }.first { it != null && kotlin.math.abs(it - next.ratio) < RATIO_MATCH }
                }
            } finally { show(null) }
        }
    }
    fun dropTarget(d: Drag): Pair<PlacedPane, DropZone>? {
        val at = d.at + stage.scroll
        val t = stage.placed.value.firstOrNull { at in it.rect }?.takeIf { it.block != d.block } ?: return null
        return t to dropZone(at.x - t.rect.left, at.y - t.rect.top, t.rect.width.toFloat(), t.rect.height.toFloat())
    }
    val pickUp by rememberUpdatedState(onPickUp)
    val dropped by rememberUpdatedState(onDropped)
    val targetTick by rememberUpdatedState(onDropTarget)
    val columnResize by rememberUpdatedState(onColumnResize)
    val dropPane by rememberUpdatedState(onDrop)

    Box(modifier.background(chrome.workspace)) {
        // Keyed by block, so a swapped pane keeps its terminal, selection and input. Without the key a slot
        // draws the old pane while typing goes to the new one.
        for (pane in showing) key(pane.block) {
            val block = pane.block
            PaneHost(
                stage, pane, strip(block), scrollable,
                menuOpen = menu == block, onMenu = { held = null; menu = if (it) block else null },
                lifted = held == block,
                onAction = { onAction(block, it) },
                onSelect = { onSelect(block) },
                onPickUp = { held = block; pickUp() },
                onDrag = { at -> menu = null; drag = Drag(block, at) },
                onDrop = { cancelled ->
                    val d = drag
                    drag = null
                    held = null
                    if (!cancelled && d != null) dropTarget(d)?.let { (t, zone) -> dropped(); onDrop(d.block, t.block, zone) }
                },
                accessibility = {
                    stage.composed.value.map { it.block }.filter { it != block }.flatMap { other ->
                        val name = strip(other).title.primary
                        listOf(
                            CustomAccessibilityAction("Swap with $name") { onDrop(block, other, DropZone.Centre); true },
                            CustomAccessibilityAction("Move beside $name") { onDrop(block, other, DropZone.Right); true },
                        )
                    }
                },
            ) { if (block in mounted) terminal(pane) }
        }

        for (divider in lines) key(divider.path) {
            val current by rememberUpdatedState(divider)
            val vertical = divider.axis == Axis.Row
            val column = vertical && scrollable
            val dot = chrome.secondary.copy(alpha = 0.65f)
            val pair = divider.adjacent
            // Above the panes, so the handle's half over the focused pane gets the touches, not the pane.
            Box(Modifier.zIndex(2f).at { handleRect(stage, divider.path, handle) }.drawBehind {
                dividerGrip(vertical, dot)
            }.semantics {
                contentDescription = if (column) "Resize column ${strip(divider.before).title.primary}"
                    else "Resize between ${strip(divider.before).title.primary} and ${strip(divider.after).title.primary}"
                progressBarRangeInfo = ProgressBarRangeInfo(divider.ratio, 0.1f..1f)
                setProgress { value ->
                    val extent = stage.dividers.value.firstOrNull { it.divider.path == divider.path }?.extent ?: 0
                    if (column) columnResize(divider.before, (value - divider.ratio) * extent / density.density)
                    else { show(Resize(divider, value.coerceIn(0.1f, 0.9f), false)); commit() }
                    true
                }
                if (pair != null) customActions = listOf(CustomAccessibilityAction(
                    "Swap ${strip(pair.first).title.primary} and ${strip(pair.second).title.primary}",
                ) { dropPane(pair.first, pair.second, DropZone.Centre); true })
            }.pointerInput(divider.path, scrollable) {
                val slop = viewConfiguration.touchSlop
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // A tap does nothing: only a drag moves a divider.
                                if (dragging) { if (gestures.panning || column) show(null) else commit() }
                                break
                            }
                            val delta = if (vertical) change.position.x - change.previousPosition.x
                                else change.position.y - change.previousPosition.y
                            val moved = if (vertical) change.position.x - down.position.x
                                else change.position.y - down.position.y
                            if (!dragging && abs(moved) > slop && !gestures.panning) {
                                dragging = true
                                show(Resize(current, current.ratio))
                            }
                            if (!dragging) continue
                            change.consume()
                            if (gestures.panning) show(null)
                            else if (column) columnResize(current.before, delta / density.density)
                            else {
                                val r = resize
                                val extent = stage.dividers.value.firstOrNull { it.divider.path == current.path }?.extent ?: 0
                                if (r != null && extent > 0) show(r.copy(ratio = (r.ratio + delta / extent).coerceIn(0.1f, 0.9f)))
                            }
                        }
                    }
                } finally {
                    if (resize?.divider?.path == divider.path && resize?.dragging == true) show(null)
                }
            })
        }

        drag?.let { d ->
            val target = dropTarget(d)
            val place = target?.let { (t, zone) ->
                val part = zone.part()
                val r = t.inner
                IntRect(
                    IntOffset((r.left + r.width * part.x).roundToInt(), (r.top + r.height * part.y).roundToInt()),
                    IntSize((r.width * part.width).roundToInt(), (r.height * part.height).roundToInt()),
                )
            }
            LaunchedEffect(target?.first?.block, target?.second) { if (target != null) targetTick() }
            if (place != null) {
                val glide = spring<IntOffset>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                val at by animateIntOffsetAsState(place.topLeft, glide, label = "drop place")
                val size by animateIntSizeAsState(place.size, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "drop size")
                // Above the focused pane (zIndex 1) and the divider handles (2), or the target pane hides it.
                Box(
                    Modifier.zIndex(3f).at { IntRect(at, size) - stage.scroll }
                        .background(chrome.accent.copy(alpha = DROP_ALPHA)).border(1.5.dp, chrome.accent),
                )
            }
            val chip = with(density) { ChipOffset.roundToPx() }
            val pop = remember { Animatable(0.6f) }
            LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) }
            DragChip(strip(d.block), Modifier.zIndex(3f).offset { IntOffset(d.at.x.roundToInt() + chip, d.at.y.roundToInt() + chip) }
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.6f) / 0.4f).coerceIn(0f, 1f); transformOrigin = TransformOrigin(0f, 0f) })
        }
    }
}

private fun DrawScope.dividerGrip(vertical: Boolean, dot: Color) {
    val step = if (vertical) Offset(0f, 5.dp.toPx()) else Offset(5.dp.toPx(), 0f)
    for (i in -1..1) drawCircle(dot, 1.25.dp.toPx(), center + step * i.toFloat())
}

// Holds no geometry, so composition skips per-frame changes.
private fun visibleLines(stage: TerminalViewport, handle: Float): List<PaneDivider> {
    val s = stage.scroll
    val area = stage.size
    return stage.dividers.value.filter { d ->
        val line = d.at - if (d.vertical) s.x else s.y
        val across = if (d.vertical) area.width else area.height
        val from = (if (d.vertical) d.bounds.top - s.y else d.bounds.left - s.x)
        val to = from + if (d.vertical) d.bounds.height else d.bounds.width
        val along = if (d.vertical) area.height else area.width
        line + handle > 0f && line - handle < across && to > 0f && from < along
    }.map { it.divider }
}

private fun handleRect(stage: TerminalViewport, path: String, handle: Float): IntRect? {
    val d = stage.dividers.value.firstOrNull { it.divider.path == path } ?: return null
    val s = stage.scroll
    val area = stage.size
    return if (d.vertical) {
        val from = (d.bounds.top - s.y).coerceAtLeast(0f)
        val to = (d.bounds.bottom - s.y).coerceAtMost(area.height.toFloat())
        val x = (d.at - s.x - handle).roundToInt()
        IntRect(x, from.roundToInt(), x + (2 * handle).roundToInt(), to.roundToInt())
    } else {
        val from = (d.bounds.left - s.x).coerceAtLeast(0f)
        val to = (d.bounds.right - s.x).coerceAtMost(area.width.toFloat())
        val y = (d.at - s.y - handle).roundToInt()
        IntRect(from.roundToInt(), y, to.roundToInt(), y + (2 * handle).roundToInt())
    }
}

private operator fun IntRect.contains(at: Offset): Boolean = at.x >= left && at.x <= right && at.y >= top && at.y <= bottom

@Composable
private fun DragChip(strip: TileStrip, modifier: Modifier) {
    HatchPill(modifier.widthIn(max = Hatch.size.menuWidth)) {
        ProgramGlyph(strip.program, Hatch.size.icon, chrome.text)
        HatchPillText(strip.title.primary, chrome.text)
    }
}

private const val DROP_ALPHA = 0.22f
private const val LAYOUT_WAIT_MILLIS = 1_500L
// The host rounds its ratio, so a match is within this tolerance.
private const val RATIO_MATCH = 0.02f
private const val MOUNT_PER_FRAME = 2

// The chip sits just below and right of the finger, so the finger doesn't hide it.
private val ChipOffset = 12.dp
