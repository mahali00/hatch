package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Rect
import dev.mahali.hatch.core.blocks
import dev.mahali.hatch.core.rects

internal enum class PaneView(val label: String) {
    Auto("Automatic"), Focus("Focus pane"), Fit("Fit all panes"), Scroll("Scrollable columns"),
}

internal data class PaneDivider(
    val path: String,
    val bounds: Rect,
    val axis: Axis,
    val ratio: Float,
    val before: BlockId,
    val after: BlockId,
    // TalkBack's Swap exchanges these two panes; a line between groups has no single pair.
    val adjacent: Pair<BlockId, BlockId>? = null,
)

internal fun Layout.dividers(bounds: Rect = Rect.Full, path: String = ""): List<PaneDivider> = when (this) {
    is Layout.Leaf -> emptyList()
    is Layout.Split -> {
        val first = if (axis == Axis.Row) bounds.copy(width = bounds.width * ratio) else bounds.copy(height = bounds.height * ratio)
        val second = if (axis == Axis.Row) bounds.copy(x = bounds.x + first.width, width = bounds.width - first.width)
            else bounds.copy(y = bounds.y + first.height, height = bounds.height - first.height)
        val left = before
        val right = after
        val adjacent = if (left is Layout.Leaf && right is Layout.Leaf) left.block to right.block else null
        listOf(PaneDivider(path, bounds, axis, ratio, before.blocks().first(), after.blocks().first(), adjacent)) +
            before.dividers(first, path + "0") + after.dividers(second, path + "1")
    }
}

internal fun Layout.resized(path: String, ratio: Float): Layout {
    if (this !is Layout.Split) return this
    return when {
        path.isEmpty() -> copy(ratio = ratio)
        path.first() == '0' -> copy(before = before.resized(path.drop(1), ratio))
        else -> copy(after = after.resized(path.drop(1), ratio))
    }
}

internal data class CanvasSize(val width: Float, val height: Float)

internal fun Layout.scrollColumns(): List<Layout> = when (this) {
    is Layout.Leaf -> listOf(this)
    is Layout.Split -> {
        val left = before.scrollColumns()
        val right = after.scrollColumns()
        if (axis == Axis.Row) left + right else (0 until maxOf(left.size, right.size)).map { i ->
            val top = left.getOrNull(i)
            val bottom = right.getOrNull(i)
            when {
                top == null -> requireNotNull(bottom)
                bottom == null -> top
                else -> Layout.Split(Axis.Column, ratio, top, bottom)
            }
        }
    }
}

internal data class ScrollStrip(val layout: Layout, val canvas: CanvasSize)

// Allowed past the screen width so a column can be panned across when its lines are long.
internal fun columnWidth(viewport: Float, fraction: Float): Float =
    (viewport * fraction).coerceIn(minOf(260f, viewport), viewport * MAX_COLUMN_SCREENS)

private const val MAX_COLUMN_SCREENS = 2f

internal fun Layout.scrollStrip(width: Float, height: Float, widths: Map<String, Float>): ScrollStrip {
    val columns = scrollColumns()
    val sizes = columns.map { col ->
        val fraction = if (columns.size == 1) 1f else col.blocks().firstNotNullOfOrNull { widths[it.value] } ?: 0.5f
        columnWidth(width, fraction)
    }.toMutableList()
    val room = width - sizes.sum()
    if (room > 0f) sizes[sizes.lastIndex] += room
    fun combine(index: Int): Layout = if (index == columns.lastIndex) columns[index] else
        Layout.Split(Axis.Row, sizes[index] / sizes.drop(index).sum(), columns[index], combine(index + 1))
    return ScrollStrip(combine(0), CanvasSize(sizes.sum(), height))
}

internal fun nextColumnWidth(current: Float, wider: Boolean): Float {
    val presets = listOf(1f / 3f, 0.5f, 2f / 3f, 1f, 1.5f, MAX_COLUMN_SCREENS)
    return if (wider) presets.firstOrNull { it > current + 0.01f } ?: MAX_COLUMN_SCREENS
        else presets.lastOrNull { it < current - 0.01f } ?: presets.first()
}
