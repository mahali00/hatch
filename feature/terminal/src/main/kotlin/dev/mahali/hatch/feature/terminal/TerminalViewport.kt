package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.GridSize
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.rects
import dev.mahali.hatch.design.Dimens
import kotlin.math.roundToInt

internal data class ViewportMetrics(
    val density: Float,
    val cellWidth: Int,
    val cellHeight: Int,
    val stripPx: Int,
    val padX: Int = 0,
    val paneGap: Dp = 6.dp,
) {
    // Rounded once for the whole gutter, so neighbours don't round it twice.
    val paneGapPx: Int get() = (paneGap.value * density).roundToInt()
}

// Main thread only. Placement follows every input; grids change only on a commit. Each call writes in
// one snapshot, so a placement and its grids are never seen apart. Rules are in ViewportTypes.kt.
internal class TerminalViewport(
    private val clock: () -> Long,
    private val after: (millis: Long, action: () -> Unit) -> () -> Unit,
    private val settleMillis: Long = 150,
) {
    private var area by mutableStateOf(IntSize.Zero)
    private var layout by mutableStateOf<Layout?>(null)
    private var mode by mutableStateOf<LayoutMode>(Tiles)

    val layoutMode: LayoutMode get() = mode
    private var focused by mutableStateOf<BlockId?>(null)
    private var columnWidths by mutableStateOf(emptyMap<String, Float>())
    private var keyboard by mutableStateOf(KeyboardInset())
    private var metrics by mutableStateOf<ViewportMetrics?>(null)
    private val cursors = mutableStateMapOf<BlockId, Float>()
    private var committed by mutableStateOf(emptyMap<BlockId, GridSize>())
    private var committedDrawn by mutableStateOf(emptyMap<BlockId, IntSize>())
    private var offset by mutableStateOf(Offset.Zero)
    private var preview by mutableStateOf<Pair<String, Float>?>(null)

    private var motion: AreaMotion = AreaMotion.Still
    private var dragging = false
    private var slideFresh = false
    private var lastAreaChange = Long.MIN_VALUE / 2
    private var cancelPending: (() -> Unit)? = null

    private val placement: State<CanvasPlacement> = derivedStateOf {
        val l = layout
        if (l == null || area == IntSize.Zero) CanvasPlacement(area, emptyMap())
        else mode.place(l, area, columnWidths, metrics?.density ?: 1f).let { p -> preview?.let { p.resized(it.first, it.second) } ?: p }
    }

    val canvas: IntSize get() = placement.value.canvas

    val scroll: Offset get() = offset

    // Only waits for the canvas while [animatesReveals]; otherwise (pages, tests) a reveal jumps.
    var revealTarget: Offset? by mutableStateOf(null)
        private set

    var animatesReveals: Boolean = false
        set(value) {
            field = value
            if (!value) atomic { revealTarget?.let { offset = clamp(it) }; revealTarget = null }
        }

    val grids: Map<BlockId, GridSize> get() = committed

    // The view and the grid the host sees must agree. Changes only on a commit, so composition never
    // recomposes per keyboard or slide frame.
    fun drawnSize(block: BlockId): IntSize? = committedDrawn[block]

    val size: IntSize get() = area

    val inset: KeyboardInset get() = keyboard

    fun liftOf(block: BlockId?): Float = block?.let { placedOf(it)?.lift } ?: 0f

    fun placedOf(block: BlockId): PlacedPane? = placed.value.firstOrNull { it.block == block }

    val placed: State<List<PlacedPane>> = derivedStateOf {
        val p = placement.value
        val window = IntRect(IntOffset(offset.x.roundToInt(), offset.y.roundToInt()), area)
        val lined = mode !is Pages
        val strip = lined && p.rects.size > 1
        val gap = if (lined) metrics?.paneGapPx ?: 0 else 0
        p.rects.map { (block, rect) ->
            val isFocused = block == focused
            val panel = inner(rect, p.canvas, gap)
            PlacedPane(
                block, rect, panel, committed[block],
                lift = if (isFocused) lift(panel, strip) else 0f,
                strip = strip,
                framed = lined && gap > 0,
                focused = isFocused,
                faded = lined && !isFocused,
                visible = rect.overlaps(window),
            )
        }
    }

    // [placed] with per-frame geometry zeroed, so composition changes only when which panes show, or how.
    val composed: State<List<PlacedPane>> = derivedStateOf {
        placed.value.map { it.copy(rect = IntRect.Zero, inner = IntRect.Zero, lift = 0f) }
    }

    val dividers: State<List<PlacedDivider>> = derivedStateOf {
        val p = placement.value
        val l = p.layout ?: return@derivedStateOf emptyList()
        // Only lines between panes: a grip on the last column's outer edge sat in Android's back-swipe strip.
        l.dividers().map { PlacedDivider(it, it.bounds.scaled(p.canvas)) }
    }

    fun area(size: IntSize) = atomic {
        if (size == area) return@atomic
        area = size
        // The canvas follows a fold or rotation at once, every frame.
        revealFocused(animate = false)
        if (motion is AreaMotion.Slide) {
            // Only the slide's first size commits, whichever of motion and area was sent first.
            if (slideFresh) { slideFresh = false; commit() }
            return@atomic
        }
        val now = clock()
        val quiet = now - lastAreaChange > settleMillis
        lastAreaChange = now
        // After a quiet spell (or the first size) a change applies at once; a burst waits for stillness.
        if (quiet || committed.isEmpty()) commit() else {
            cancelPending?.invoke()
            cancelPending = after(settleMillis) { cancelPending = null; atomic { commit() } }
        }
    }

    // A mode switch commits even mid-drag, since a grid from the old mode would be stale. A layout
    // change commits unless a divider is being dragged.
    fun layout(layout: Layout?, mode: LayoutMode, focused: BlockId?, columnWidths: Map<String, Float> = emptyMap()) = atomic {
        val modeChanged = mode != this.mode
        val shapeChanged = layout != this.layout || columnWidths != this.columnWidths
        val focusChanged = focused != this.focused
        this.layout = layout
        this.mode = mode
        this.focused = focused
        this.columnWidths = columnWidths
        // A dragged ratio belongs to the mode it was dragged in.
        if (modeChanged) preview = null
        // Not while a divider is dragged: scrolling the focused pane into view moved the canvas under the finger.
        if (modeChanged || (shapeChanged && !dragging) || focusChanged) revealFocused(animate = !modeChanged)
        if (modeChanged) commit(keep = false) else if (shapeChanged) commit()
    }

    fun keyboard(inset: KeyboardInset) = atomic {
        val shown = inset.visible != keyboard.visible
        keyboard = inset
        if (shown) revealFocused()
    }

    // A slide commits once, for its first size (before or after this call), and holds until Still.
    // A burst commits its first step at once and settles the rest as [area] does.
    fun motion(motion: AreaMotion) = atomic {
        this.motion = motion
        when (motion) {
            is AreaMotion.Slide -> { commit(); slideFresh = true }
            AreaMotion.Burst -> commit()
            AreaMotion.Still -> { cancelPending?.invoke(); cancelPending = null; commit() }
        }
    }

    fun cursor(block: BlockId, linePx: Float?) = atomic {
        if (linePx == null || linePx < 0f) cursors.remove(block) else cursors[block] = linePx
    }

    fun metrics(metrics: ViewportMetrics) = atomic {
        if (metrics == this.metrics) return@atomic
        this.metrics = metrics
        commit()
    }

    fun scrollBy(dx: Float, dy: Float) = atomic { revealTarget = null; offset = clamp(Offset(offset.x + dx, offset.y + dy)) }

    fun scrollTo(to: Offset) = atomic { offset = clamp(to) }

    fun revealArrived(target: Offset) = atomic { if (revealTarget == target) revealTarget = null }

    fun reveal() = atomic { revealFocused() }

    private fun revealFocused(animate: Boolean = true) {
        val r = placement.value.rects[focused]
        val to = clamp(if (r == null) offset else Offset(
            revealed(offset.x, area.width.toFloat(), r.left.toFloat(), r.width.toFloat()),
            revealed(offset.y, area.height.toFloat(), r.top.toFloat(), r.height.toFloat()),
        ))
        if (animate && animatesReveals && to != offset) revealTarget = to
        else { offset = to; revealTarget = null }
    }

    fun hostRatio(path: String): Float? = layout?.dividers()?.firstOrNull { it.path == path }?.ratio

    fun beginDividerDrag() { dragging = true }

    fun preview(path: String?, ratio: Float = 0f) = atomic { preview = path?.let { it to ratio } }

    fun endDividerDrag() = atomic { dragging = false; commit() }

    private fun commit(keep: Boolean = dragging) {
        val target = targetSizes()
        val grids = if (keep) target.mapValues { (b, t) -> committed[b] ?: t.second } else target.mapValues { it.value.second }
        val drawn = if (keep) target.mapValues { (b, t) -> committedDrawn[b] ?: t.first } else target.mapValues { it.value.first }
        if (grids != committed) committed = grids
        if (drawn != committedDrawn) committedDrawn = drawn
    }

    private fun targetSizes(): Map<BlockId, Pair<IntSize, GridSize>> {
        val m = metrics ?: return emptyMap()
        val p = placement.value
        val strip = mode !is Pages && p.rects.size > 1
        return p.rects.mapValues { (_, r) ->
            val content = contentSize(r, p.canvas, mode !is Pages, strip, m)
            content to GridSize(
                ((content.width - m.padX * 2) / m.cellWidth).coerceAtLeast(1),
                (content.height / m.cellHeight).coerceAtLeast(1),
                m.cellWidth, m.cellHeight,
            )
        }
    }

    private fun lift(rect: IntRect, strip: Boolean): Float {
        // Unknown cursor: stay put (the line arrives a frame after the keyboard starts).
        val line = focused?.let { cursors[it] } ?: return 0f
        val overlap = keyboard.overlapPx
        val cursor = rect.top - offset.y + (if (strip) metrics?.stripPx ?: 0 else 0) + line
        return (cursor - (area.height - overlap)).coerceIn(0f, overlap)
    }

    private fun clamp(o: Offset): Offset {
        val c = placement.value.canvas
        return Offset(
            o.x.coerceIn(0f, (c.width - area.width).coerceAtLeast(0).toFloat()),
            o.y.coerceIn(0f, (c.height - area.height).coerceAtLeast(0).toFloat()),
        )
    }

    private inline fun atomic(crossinline block: () -> Unit) = Snapshot.withMutableSnapshot { block() }
}

internal fun contentSize(slot: IntRect, canvas: IntSize, lined: Boolean, strip: Boolean, m: ViewportMetrics): IntSize {
    val r = inner(slot, canvas, if (lined) m.paneGapPx else 0)
    return IntSize(r.width, (r.height - if (strip) m.stripPx else 0).coerceAtLeast(0))
}

private fun inner(slot: IntRect, canvas: IntSize, gap: Int): IntRect {
    // Offset and size round separately, so an edge tile can end a pixel off the canvas edge.
    val left = if (slot.left > 1) gap / 2 else gap
    val top = if (slot.top > 1) gap / 2 else gap
    val right = if (slot.right < canvas.width - 1) gap - gap / 2 else gap
    val bottom = if (slot.bottom < canvas.height - 1) gap - gap / 2 else gap
    return IntRect(slot.left + left, slot.top + top,
        (slot.right - right).coerceAtLeast(slot.left + left),
        (slot.bottom - bottom).coerceAtLeast(slot.top + top))
}

private fun revealed(offset: Float, viewport: Float, start: Float, size: Float): Float = when {
    start < offset -> start
    start + size > offset + viewport -> if (size <= viewport) start + size - viewport else start
    else -> offset
}
