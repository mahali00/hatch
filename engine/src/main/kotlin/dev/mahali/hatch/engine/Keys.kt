package dev.mahali.hatch.engine

import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKey
import dev.mahali.hatch.protocol.KeyEvent

/** Modifiers held for one key, in the server's order: shift, ctrl, alt, super. */
public data class Mods(val shift: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val meta: Boolean = false) {
    public fun wire(): String = listOfNotNull("shift".takeIf { shift }, "ctrl".takeIf { ctrl }, "alt".takeIf { alt }, "super".takeIf { meta }).joinToString("+")
    public val any: Boolean get() = shift || ctrl || alt || meta

    public operator fun plus(other: Mods): Mods = Mods(shift || other.shift, ctrl || other.ctrl, alt || other.alt, meta || other.meta)

    public companion object {
        public val None: Mods = Mods()
        public fun of(event: AndroidKey): Mods = Mods(event.isShiftPressed, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed)
    }
}

/** Committed text as a key event. Letters and digits use their physical key, so server bindings like ctrl+n match. */
public fun keyForText(text: String, mods: Mods = Mods.None): KeyEvent {
    val c = text.singleOrNull()
    val key = when {
        c == null -> "unidentified"
        c in 'a'..'z' || c in 'A'..'Z' -> "key_${c.lowercaseChar()}"
        c in '0'..'9' -> "digit_$c"
        c == ' ' -> "space"
        c == '\n' -> "enter"
        // Shifted digit-row symbols name their digit key, so Ctrl+^ is ctrl+shift+6.
        else -> punctuation[c] ?: shiftedPairs[c]?.takeIf { it.isDigit() }?.let { "digit_$it" } ?: "unidentified"
    }
    val shifted = c != null && c.isUpperCase()
    val unshifted = c?.let { shiftedPairs[it] ?: it.lowercaseChar() }?.code ?: text.codePointAt(0)
    val chord = mods.ctrl || mods.alt || mods.meta
    val shift = mods.shift || shifted || (c != null && c in shiftedPairs)
    val committed = if (mods.shift && !chord && c != null) {
        (unshiftedPairs[c] ?: c.uppercaseChar()).toString()
    } else text
    return KeyEvent(
        key = key,
        mods = mods.copy(shift = shift).wire(),
        // Shift that made the character is consumed; the program gets the character, not a chord.
        consumedMods = if (shift && !chord) "shift" else "",
        text = if (chord) "" else committed,
        unshiftedCodepoint = unshifted,
    )
}

public fun namedKey(name: String, mods: Mods = Mods.None): KeyEvent = KeyEvent(key = name, mods = mods.wire())

public class KeyTranslator {
    private var accent = 0

    /** Null when Android should handle the key (volume, media, numpad with Num Lock off). */
    public fun translate(event: AndroidKey, extra: Mods = Mods.None): List<KeyEvent>? {
        var mods = Mods.of(event) + extra
        special[event.keyCode]?.let { return flush() + namedKey(it, mods) }
        if (AndroidKey.isModifierKey(event.keyCode)) return emptyList()
        val meta = event.metaState
        val chord = mods.ctrl || mods.alt || mods.meta
        // Android gives no character under Ctrl, so read it without Ctrl, Alt and Meta.
        // Under a chord Caps Lock is dropped too, or Ctrl+C with Caps Lock reads as Ctrl+Shift+C.
        val plainMeta = meta and (CTRL or ALT or META or (if (chord) AndroidKey.META_CAPS_LOCK_ON else 0)).inv()
        var ch = event.getUnicodeChar(plainMeta)
        // AltGr types characters on most non-US layouts; it is Alt only where it gives none.
        if (meta and AndroidKey.META_ALT_RIGHT_ON != 0 && !mods.ctrl && !mods.meta) {
            val altGr = event.getUnicodeChar(meta and (CTRL or META or AndroidKey.META_ALT_LEFT_ON).inv())
            if (altGr != 0 && altGr != ch) { ch = altGr; mods = mods.copy(alt = extra.alt) }
        }
        if (ch == 0) return null
        if (ch and KeyCharacterMap.COMBINING_ACCENT != 0) {
            val dead = ch and KeyCharacterMap.COMBINING_ACCENT_MASK
            if (accent == dead) return flush()
            val before = flush()
            accent = dead
            return before
        }
        val before = if (accent != 0 && !(mods.ctrl || mods.alt || mods.meta)) {
            val combined = KeyCharacterMap.getDeadChar(accent, ch)
            if (combined != 0) { accent = 0; ch = combined; emptyList() } else flush()
        } else flush()
        return before + keyForText(String(Character.toChars(lookAlikes[ch] ?: ch)), mods)
    }

    private fun flush(): List<KeyEvent> {
        if (accent == 0) return emptyList()
        val spacing = KeyCharacterMap.getDeadChar(accent, ' '.code).takeIf { it != 0 } ?: accent
        accent = 0
        return listOf(keyForText(String(Character.toChars(spacing))))
    }

    private companion object {
        const val CTRL = AndroidKey.META_CTRL_MASK
        const val ALT = AndroidKey.META_ALT_MASK
        const val META = AndroidKey.META_META_MASK

        /** Some Bluetooth keyboards send modifier letters for ~ ` ^. */
        val lookAlikes = mapOf(0x02DC to '~'.code, 0x02CB to '`'.code, 0x02C6 to '^'.code)
    }
}

private val special = mapOf(
    AndroidKey.KEYCODE_ENTER to "enter",
    AndroidKey.KEYCODE_NUMPAD_ENTER to "numpad_enter",
    AndroidKey.KEYCODE_DEL to "backspace",
    AndroidKey.KEYCODE_FORWARD_DEL to "delete",
    AndroidKey.KEYCODE_TAB to "tab",
    AndroidKey.KEYCODE_ESCAPE to "escape",
    AndroidKey.KEYCODE_DPAD_UP to "arrow_up",
    AndroidKey.KEYCODE_DPAD_DOWN to "arrow_down",
    AndroidKey.KEYCODE_DPAD_LEFT to "arrow_left",
    AndroidKey.KEYCODE_DPAD_RIGHT to "arrow_right",
    AndroidKey.KEYCODE_MOVE_HOME to "home",
    AndroidKey.KEYCODE_MOVE_END to "end",
    AndroidKey.KEYCODE_PAGE_UP to "page_up",
    AndroidKey.KEYCODE_PAGE_DOWN to "page_down",
    AndroidKey.KEYCODE_INSERT to "insert",
    AndroidKey.KEYCODE_F1 to "f1", AndroidKey.KEYCODE_F2 to "f2", AndroidKey.KEYCODE_F3 to "f3",
    AndroidKey.KEYCODE_F4 to "f4", AndroidKey.KEYCODE_F5 to "f5", AndroidKey.KEYCODE_F6 to "f6",
    AndroidKey.KEYCODE_F7 to "f7", AndroidKey.KEYCODE_F8 to "f8", AndroidKey.KEYCODE_F9 to "f9",
    AndroidKey.KEYCODE_F10 to "f10", AndroidKey.KEYCODE_F11 to "f11", AndroidKey.KEYCODE_F12 to "f12",
)

/** Characters typed with shift, and the character on the same key without it (US layout). */
private val shiftedPairs = mapOf(
    '~' to '`', '!' to '1', '@' to '2', '#' to '3', '$' to '4', '%' to '5', '^' to '6', '&' to '7', '*' to '8',
    '(' to '9', ')' to '0', '_' to '-', '+' to '=', '{' to '[', '}' to ']', '|' to '\\', ':' to ';', '"' to '\'',
    '<' to ',', '>' to '.', '?' to '/',
)

private val unshiftedPairs = shiftedPairs.entries.associate { (shifted, plain) -> plain to shifted }

private val punctuation = mapOf(
    '`' to "backquote", '~' to "backquote", '-' to "minus", '_' to "minus", '=' to "equal", '+' to "equal",
    '[' to "bracket_left", '{' to "bracket_left", ']' to "bracket_right", '}' to "bracket_right",
    '\\' to "backslash", '|' to "backslash", ';' to "semicolon", ':' to "semicolon", '\'' to "quote", '"' to "quote",
    ',' to "comma", '<' to "comma", '.' to "period", '>' to "period", '/' to "slash", '?' to "slash",
)
