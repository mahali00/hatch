package dev.mahali.hatch.engine

import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Keys as a hardware keyboard sends them, through this device's real (US) key character map. */
@RunWith(AndroidJUnit4::class)
class HardwareKeyTest {
    private val keys = KeyTranslator()
    private fun down(code: Int, meta: Int = 0) =
        AndroidKey(0, 0, AndroidKey.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)

    private val ctrl = AndroidKey.META_CTRL_ON or AndroidKey.META_CTRL_LEFT_ON
    private val shift = AndroidKey.META_SHIFT_ON or AndroidKey.META_SHIFT_LEFT_ON

    @Test fun ctrlLetterIsSentAsTheChord() =
        assertEquals(listOf(keyForText("c", Mods(ctrl = true))), keys.translate(down(AndroidKey.KEYCODE_C, ctrl)))

    @Test fun capsLockDoesNotAddShiftToCtrl() =
        assertEquals(listOf(keyForText("c", Mods(ctrl = true))), keys.translate(down(AndroidKey.KEYCODE_C, ctrl or AndroidKey.META_CAPS_LOCK_ON)))

    @Test fun shiftedPunctuationStillTypes() =
        assertEquals(listOf(keyForText(":", Mods(shift = true))), keys.translate(down(AndroidKey.KEYCODE_SEMICOLON, shift)))

    @Test fun ctrlCaretNamesTheDigitKey() =
        assertEquals("digit_6", keys.translate(down(AndroidKey.KEYCODE_6, ctrl or shift))!!.single().key)

    @Test fun rightAltOnUsLayoutIsStillAlt() {
        val alt = AndroidKey.META_ALT_ON or AndroidKey.META_ALT_RIGHT_ON
        assertEquals(listOf(keyForText("b", Mods(alt = true))), keys.translate(down(AndroidKey.KEYCODE_B, alt)))
    }

    @Test fun modifierAloneSendsNothing() = assertEquals(emptyList<Any>(), keys.translate(down(AndroidKey.KEYCODE_SHIFT_LEFT, shift)))

    @Test fun volumeIsLeftToAndroid() = assertNull(keys.translate(down(AndroidKey.KEYCODE_VOLUME_UP)))
}
