package dev.mahali.hatch.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val bg = 0x0A0A0A

    private fun ratio(a: Int, b: Int): Float {
        val la = Contrast.luminance(a); val lb = Contrast.luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test fun `dim text is lifted just to 3 to 1, not to white`() {
        val out = Contrast.readable(0x303030, bg)
        assertTrue(ratio(out, bg) >= 3f)
        assertTrue(ratio(out, bg) < 3.3f)
    }

    @Test fun `a fade keeps its order`() {
        // OpenCode fades tab labels letter by letter; lifting must never make a dimmer letter brighter.
        val fade = listOf(0x202020, 0x303030, 0x404040, 0x606060, 0x808080)
        val lifted = fade.map { Contrast.luminance(Contrast.readable(it, bg)) }
        assertEquals(lifted.sorted(), lifted)
    }
}
