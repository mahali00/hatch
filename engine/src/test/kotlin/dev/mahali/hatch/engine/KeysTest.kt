package dev.mahali.hatch.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class KeysTest {
    @Test fun `a shifted symbol is the character, with shift consumed`() {
        val pipe = keyForText("|")
        assertEquals("backslash", pipe.key)
        assertEquals("|", pipe.text)
        assertEquals("shift", pipe.mods)
        assertEquals("shift", pipe.consumedMods)
        assertEquals('\\'.code, pipe.unshiftedCodepoint)
    }

    @Test fun `key bar shift produces the shifted character before consuming shift`() {
        for ((plain, shifted) in listOf("a" to "A", "1" to "!", "/" to "?", "[" to "{", "é" to "É")) {
            val key = keyForText(plain, Mods(shift = true))
            assertEquals(shifted, key.text)
            assertEquals("shift", key.mods)
            assertEquals("shift", key.consumedMods)
            assertEquals(plain.single().code, key.unshiftedCodepoint)
        }
    }

    @Test fun `already shifted text stays shifted and other modifiers remain chords`() {
        assertEquals("A", keyForText("A", Mods(shift = true)).text)
        assertEquals("!", keyForText("!", Mods(shift = true)).text)
        val chord = keyForText("a", Mods(shift = true, ctrl = true))
        assertEquals("shift+ctrl", chord.mods)
        assertEquals("", chord.text)
        assertEquals("", chord.consumedMods)
    }

}
