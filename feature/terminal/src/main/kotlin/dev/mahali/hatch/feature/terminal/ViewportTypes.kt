package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.GridSize
import dev.mahali.hatch.core.Layout

// The workspace kit's types. `TerminalViewport` (built on these) is the one owner of where each
// pane of a tab is on screen, the grid it is drawn at, and how far it rises for the keyboard.
// Its rules, each pinned by a unit test:
//  1. A motion commits each pane's grid once, at its start; movement is translation, never layout.
//  2. Nothing lifts while the focused pane's cursor line is unknown.
//  3. A mode switch and the grid change it causes commit together, as one placement.
//  4. A divider drag freezes grids until release.
//  5. A pane's committed grid comes from its slot's final size, never from a size it had in
//     another mode (no stale grids).

// Read in layout and draw only.
internal data class PlacedPane(
    val block: BlockId,
    val rect: IntRect,
    val inner: IntRect,
    /** The grid committed for the slot's final size, or null until it is known. */
    val grid: GridSize?,
    val lift: Float,
    val strip: Boolean,
    val framed: Boolean,
    val focused: Boolean,
    val faded: Boolean,
    val visible: Boolean,
)

internal interface LayoutMode {
    fun place(layout: Layout, area: IntSize, columnWidths: Map<String, Float>, density: Float): CanvasPlacement
}

internal data class CanvasPlacement(val canvas: IntSize, val rects: Map<BlockId, IntRect>, val layout: Layout? = null)

internal data class PlacedDivider(val divider: PaneDivider, val bounds: IntRect) {
    val vertical: Boolean get() = divider.axis == Axis.Row

    val at: Float get() = if (vertical) bounds.left + bounds.width * divider.ratio else bounds.top + bounds.height * divider.ratio

    val extent: Int get() = if (vertical) bounds.width else bounds.height
}

internal data class KeyboardInset(
    val nowPx: Float = 0f,
    val targetPx: Float = 0f,
    val keyBarPx: Float = 0f,
    val navigationPx: Float = 0f,
) {
    val overlapPx: Float get() {
        val full = maxOf(targetPx, nowPx)
        val keyBar = if (full > 0f) keyBarPx * (nowPx / full).coerceIn(0f, 1f) else 0f
        return (nowPx + keyBar - navigationPx).coerceAtLeast(0f)
    }

    val visible: Boolean get() = targetPx > 0f || nowPx > 0f

    val raisePx: Float get() = (nowPx - navigationPx).coerceAtLeast(0f)
}

// The viewport applies one policy to every motion: grids commit once, at the start; movement is a layer translation.
public sealed interface AreaMotion {
    public data object Still : AreaMotion

    // Size is already final; only the offset is read while drawing.
    public data class Slide(val offsetX: () -> Float) : AreaMotion

    // Commits once at the start, then once when it stops.
    public data object Burst : AreaMotion
}
