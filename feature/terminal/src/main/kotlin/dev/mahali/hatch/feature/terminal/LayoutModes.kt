package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Rect
import dev.mahali.hatch.core.blocks
import dev.mahali.hatch.core.rects
import kotlin.math.roundToInt

// Floating panes page too, so [panes] adds them to the layout's order.
internal data class Pages(val panes: List<BlockId>? = null) : LayoutMode {
    override fun place(layout: Layout, area: IntSize, columnWidths: Map<String, Float>, density: Float): CanvasPlacement {
        val order = panes ?: layout.blocks()
        val rects = order.withIndex().associate { (i, b) -> b to IntRect(IntOffset(i * area.width, 0), area) }
        return CanvasPlacement(IntSize(area.width * order.size.coerceAtLeast(1), area.height), rects)
    }
}

// Same placement as PaneTiles.
internal data object Tiles : LayoutMode {
    override fun place(layout: Layout, area: IntSize, columnWidths: Map<String, Float>, density: Float): CanvasPlacement =
        CanvasPlacement(area, layout.rects().mapValues { it.value.scaled(area) }, layout)
}

// The canvas is wider than the area; column widths are in dp.
internal data object Columns : LayoutMode {
    override fun place(layout: Layout, area: IntSize, columnWidths: Map<String, Float>, density: Float): CanvasPlacement {
        val strip = layout.scrollStrip(area.width / density, area.height / density, columnWidths)
        val canvas = IntSize((strip.canvas.width * density).roundToInt(), area.height)
        return CanvasPlacement(canvas, strip.layout.rects().mapValues { it.value.scaled(canvas) }, strip.layout)
    }
}

internal fun CanvasPlacement.resized(path: String, ratio: Float): CanvasPlacement {
    val l = layout?.resized(path, ratio) ?: return this
    return CanvasPlacement(canvas, l.rects().mapValues { it.value.scaled(canvas) }, l)
}

// Round offset and size separately, as Compose's offset and size modifiers do.
internal fun Rect.scaled(to: IntSize): IntRect = IntRect(
    IntOffset((x * to.width).roundToInt(), (y * to.height).roundToInt()),
    IntSize((width * to.width).roundToInt(), (height * to.height).roundToInt()),
)
