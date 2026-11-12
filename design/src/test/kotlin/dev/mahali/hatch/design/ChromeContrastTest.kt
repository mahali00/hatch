package dev.mahali.hatch.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2 minimums: 4.5:1 for body text, 3:1 for secondary text and glyphs. */
class ChromeContrastTest {
    private val themes = Themes.parse(File("src/main/assets/themes.json").readText())

    @Test fun foregroundsReadOnTheirFills() {
        assertTrue("No built-in themes loaded", themes.isNotEmpty())
        val failures = themes.flatMap { theme ->
            listOf(true, false).flatMap { tint ->
                val c = Chrome.of(theme, tint)
                listOf(
                    Check("text on background", c.text, c.background, BODY),
                    Check("text on surface", c.text, c.surface, BODY),
                    Check("text on card", c.text, c.card, BODY),
                    Check("text on control", c.text, c.control, BODY),
                    Check("secondary on background", c.secondary, c.background, LARGE),
                    Check("secondary on card", c.secondary, c.card, LARGE),
                    Check("onAccent on accent", c.onAccent, c.accent, BODY),
                    Check("onAccentContainer on accentContainer", c.onAccentContainer, c.accentContainer, LARGE),
                    // Danger fills carry glyphs only, so the 3:1 minimum applies.
                    Check("onDanger on danger", c.onDanger, c.danger, LARGE),
                ).mapNotNull { check ->
                    val ratio = contrast(check.fg, check.bg)
                    "${theme.id} tint=$tint ${check.name}: %.2f < ${check.min}".format(ratio).takeIf { ratio < check.min }
                }
            }
        }
        assertEquals("", failures.joinToString("\n"))
    }

    @Test fun tintOffIsNeutralButTheTerminalKeepsTheTheme() {
        for (theme in themes) {
            val c = Chrome.of(theme, tint = false)
            assertEquals((if (theme.dark) Chrome.NeutralDark else Chrome.NeutralLight).copy(terminal = c.terminal), c)
            assertEquals(Color(theme.background), c.terminal)
        }
    }

    @Test fun rolesStayDistinct() {
        for (theme in themes) for (tint in listOf(true, false)) {
            val c = Chrome.of(theme, tint)
            assertTrue("${theme.id}: control must stand off the card", contrast(c.control, c.card) > 1.05)
            assertTrue("${theme.id}: accentContainer is not the card", c.accentContainer != c.card)
        }
    }

    @Test fun workspaceMatchesInactiveSurfaceWithoutChangingTerminalBackground() {
        for (theme in themes) for (tint in listOf(true, false)) {
            val c = Chrome.of(theme, tint)
            val fade = if (theme.dark) 0.8f else 0.98f
            assertEquals(Color(theme.background), c.terminal)
            // Compose stores sRGB colors as 8-bit channels, just like the rendered surface.
            assertEquals(c.terminal.red * fade, c.workspace.red, 0.5f / 255f)
            assertEquals(c.terminal.green * fade, c.workspace.green, 0.5f / 255f)
            assertEquals(c.terminal.blue * fade, c.workspace.blue, 0.5f / 255f)
            assertEquals(1f, c.workspace.alpha, 0f)
        }
    }

    private class Check(val name: String, val fg: Color, val bg: Color, val min: Double)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        fun channel(shift: Int): Double {
            val v = (argb shr shift and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        const val BODY = 4.5
        const val LARGE = 3.0
    }
}
