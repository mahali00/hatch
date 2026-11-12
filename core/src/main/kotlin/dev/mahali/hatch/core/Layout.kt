package dev.mahali.hatch.core

public enum class Axis {
    Row,
    Column,
}

public data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    public companion object {
        public val Full: Rect = Rect(0f, 0f, 1f, 1f)
    }
}

/** The binary split tree the server sends; positions and reading order derive from it. */
public sealed interface Layout {
    public data class Leaf(val block: BlockId) : Layout

    public data class Split(val axis: Axis, val ratio: Float, val before: Layout, val after: Layout) : Layout
}

public fun Layout.blocks(): List<BlockId> = buildList { collectBlocks(this@blocks) }

public fun Layout.rects(bounds: Rect = Rect.Full): Map<BlockId, Rect> = buildMap { place(this@rects, bounds) }

private fun MutableList<BlockId>.collectBlocks(layout: Layout) {
    when (layout) {
        is Layout.Leaf -> add(layout.block)
        is Layout.Split -> {
            collectBlocks(layout.before)
            collectBlocks(layout.after)
        }
    }
}

private fun MutableMap<BlockId, Rect>.place(layout: Layout, r: Rect) {
    when (layout) {
        is Layout.Leaf -> put(layout.block, r)
        is Layout.Split -> when (layout.axis) {
            Axis.Row -> {
                val w = r.width * layout.ratio
                place(layout.before, r.copy(width = w))
                place(layout.after, r.copy(x = r.x + w, width = r.width - w))
            }
            Axis.Column -> {
                val h = r.height * layout.ratio
                place(layout.before, r.copy(height = h))
                place(layout.after, r.copy(y = r.y + h, height = r.height - h))
            }
        }
    }
}
