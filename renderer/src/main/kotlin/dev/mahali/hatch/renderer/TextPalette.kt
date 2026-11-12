package dev.mahali.hatch.renderer

import androidx.collection.MutableIntIntMap

internal class TextPalette {
    private var palette: List<Int> = emptyList()
    private val nearest = MutableIntIntMap()

    fun map(color: Int, codepoint: Int, colors: List<Int>): Int {
        if (colors.isEmpty() || isGraphic(codepoint)) return color
        if (palette != colors) { palette = colors; nearest.clear() }
        if (color in nearest) return nearest[color]
        val mapped = colors.minBy { candidate ->
            val r = ((color shr 16) and 255) - ((candidate shr 16) and 255)
            val g = ((color shr 8) and 255) - ((candidate shr 8) and 255)
            val b = (color and 255) - (candidate and 255)
            30 * r * r + 59 * g * g + 11 * b * b
        }
        if (nearest.size >= 4096) nearest.clear()
        nearest[color] = mapped
        return mapped
    }
}

internal fun isGraphic(cp: Int): Boolean =
    cp in 0x2500..0x259F || cp in 0xE0B0..0xE0D7 || cp in 0x1FB00..0x1FBFF
