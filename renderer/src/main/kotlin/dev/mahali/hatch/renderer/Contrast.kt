package dev.mahali.hatch.renderer

/** Keeps text at least 3:1 against its background (WCAG luminance). */
internal object Contrast {
    fun luminance(c: Int): Float {
        fun ch(v: Int): Float { val s = v / 255f; return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055) / 1.055), 2.4).toFloat() }
        return 0.2126f * ch((c shr 16) and 0xFF) + 0.7152f * ch((c shr 8) and 0xFF) + 0.0722f * ch(c and 0xFF)
    }

    fun readable(fg: Int, bg: Int): Int {
        val lf = luminance(fg); val lb = luminance(bg)
        val ratio = (maxOf(lf, lb) + 0.05f) / (minOf(lf, lb) + 0.05f)
        if (ratio >= 3f) return fg
        // Only as far as 3:1 needs, keeping the hue. A fixed grey inverted fades: OpenCode's dimmest letters turned brightest.
        val target = if (lb > 0.4f) 0x000000 else 0xFFFFFF
        var lo = 0f; var hi = 1f
        repeat(12) {
            val mid = (lo + hi) / 2f
            val lm = luminance(mix(fg, target, mid))
            val r = (maxOf(lm, lb) + 0.05f) / (minOf(lm, lb) + 0.05f)
            if (r >= 3f) hi = mid else lo = mid
        }
        return mix(fg, target, hi)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF; val y = (b shr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
