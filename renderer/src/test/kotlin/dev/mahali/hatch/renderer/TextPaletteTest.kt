package dev.mahali.hatch.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPaletteTest {
    @Test fun `text maps to nearest theme color and graphics remain exact`() {
        val palette = TextPalette()
        val colors = listOf(0x000000, 0xFF0000, 0xFFFFFF)
        assertEquals(0xFF0000, palette.map(0xEF1212, 'A'.code, colors))
        assertEquals(0xEF1212, palette.map(0xEF1212, 0x2588, colors))
        assertEquals(0xEF1212, palette.map(0xEF1212, 0xE0B0, colors))
        assertEquals(0xEF1212, palette.map(0xEF1212, 'A'.code, emptyList()))
    }

    @Test fun `theme changes invalidate cached mappings`() {
        val palette = TextPalette()
        assertEquals(0xFF0000, palette.map(0xEF1212, 'A'.code, listOf(0xFF0000)))
        assertEquals(0x00FF00, palette.map(0xEF1212, 'A'.code, listOf(0x00FF00)))
    }
}
