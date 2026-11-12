package dev.mahali.hatch.feature.terminal

internal enum class ScrollMode {
    History,

    Wheel,

    // Alternate scroll (1007) turns the drag into arrow keys for a full-screen program.
    Arrows,

    None,
}

internal fun scrollMode(alternateScreen: Boolean, mouseTracking: Boolean, alternateScroll: Boolean): ScrollMode = when {
    mouseTracking -> ScrollMode.Wheel
    alternateScreen && alternateScroll -> ScrollMode.Arrows
    alternateScreen -> ScrollMode.None
    else -> ScrollMode.History
}

/** Turns drag pixels into whole lines, carrying the remainder so slow drags still move. */
internal class LineAccumulator(private val lineHeight: Float) {
    private var carry = 0f

    /** Adds [pixels] (positive downward) and returns the whole lines crossed, positive downward. */
    fun add(pixels: Float): Int {
        carry += pixels
        val lines = (carry / lineHeight).toInt()
        carry -= lines * lineHeight
        return lines
    }
}
