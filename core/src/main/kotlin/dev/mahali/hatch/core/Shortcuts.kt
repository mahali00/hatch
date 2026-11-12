package dev.mahali.hatch.core

/** A hardware keyboard shortcut: an Android key code and its modifiers. */
public data class KeyCombo(
    val key: Int,
    val meta: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    public fun encode(): String = listOfNotNull("ctrl".takeIf { ctrl }, "alt".takeIf { alt }, "shift".takeIf { shift }, "meta".takeIf { meta }, "$key").joinToString("+")

    public val label: String
        get() = buildString {
            if (ctrl) append('⌃')
            if (alt) append('⌥')
            if (shift) append('⇧')
            if (meta) append('⌘')
            append(keyName(key))
        }

    public companion object {
        public fun decode(text: String): KeyCombo? {
            val parts = text.split('+')
            val key = parts.lastOrNull()?.toIntOrNull() ?: return null
            return KeyCombo(key, "meta" in parts, "ctrl" in parts, "alt" in parts, "shift" in parts)
        }

        public fun keyName(code: Int): String = when (code) {
            in KEY_A..KEY_Z -> ('A' + (code - KEY_A)).toString()
            in KEY_0..KEY_9 -> ('0' + (code - KEY_0)).toString()
            KEY_COMMA -> ","
            KEY_PERIOD -> "."
            KEY_LEFT_BRACKET -> "["
            KEY_RIGHT_BRACKET -> "]"
            KEY_SLASH -> "/"
            KEY_BACKSLASH -> "\\"
            KEY_MINUS -> "-"
            KEY_EQUALS -> "="
            KEY_SEMICOLON -> ";"
            KEY_GRAVE -> "`"
            KEY_TAB -> "⇥"
            KEY_ENTER -> "↩"
            KEY_SPACE -> "Space"
            KEY_LEFT -> "←"
            KEY_RIGHT -> "→"
            KEY_UP -> "↑"
            KEY_DOWN -> "↓"
            else -> "Key $code"
        }

        // android.view.KeyEvent codes, copied so core needs no Android.
        public const val KEY_0: Int = 7
        public const val KEY_9: Int = 16
        public const val KEY_A: Int = 29
        public const val KEY_Z: Int = 54
        public const val KEY_COMMA: Int = 55
        public const val KEY_PERIOD: Int = 56
        public const val KEY_TAB: Int = 61
        public const val KEY_SPACE: Int = 62
        public const val KEY_ENTER: Int = 66
        public const val KEY_GRAVE: Int = 68
        public const val KEY_MINUS: Int = 69
        public const val KEY_EQUALS: Int = 70
        public const val KEY_LEFT_BRACKET: Int = 71
        public const val KEY_RIGHT_BRACKET: Int = 72
        public const val KEY_BACKSLASH: Int = 73
        public const val KEY_SEMICOLON: Int = 74
        public const val KEY_SLASH: Int = 76
        public const val KEY_UP: Int = 19
        public const val KEY_DOWN: Int = 20
        public const val KEY_LEFT: Int = 21
        public const val KEY_RIGHT: Int = 22
    }
}

public enum class AppShortcut(public val title: String, public val group: String, public val default: KeyCombo?) {
    CommandPalette("Command Palette", "App", KeyCombo(KeyCombo.KEY_A + ('P' - 'A'), meta = true, shift = true)),
    Settings("Settings", "App", KeyCombo(KeyCombo.KEY_COMMA, meta = true)),
    ToggleSidebar("Toggle Sidebar", "Window", KeyCombo(KeyCombo.KEY_BACKSLASH, meta = true)), // ⌃⌘S is taken by Android's screenshot.
    NewSession("New Session", "Session", KeyCombo(KeyCombo.KEY_A + ('N' - 'A'), meta = true, shift = true)),
    NextSession("Next Session", "Session", KeyCombo(KeyCombo.KEY_DOWN, meta = true, alt = true)),
    PreviousSession("Previous Session", "Session", KeyCombo(KeyCombo.KEY_UP, meta = true, alt = true)),
    NewTab("New Tab", "Tab", KeyCombo(KeyCombo.KEY_A + ('T' - 'A'), meta = true)),
    CloseTab("Close Tab", "Tab", KeyCombo(KeyCombo.KEY_A + ('W' - 'A'), meta = true, alt = true)),
    NextTab("Next Tab", "Tab", KeyCombo(KeyCombo.KEY_RIGHT_BRACKET, meta = true, shift = true)),
    PreviousTab("Previous Tab", "Tab", KeyCombo(KeyCombo.KEY_LEFT_BRACKET, meta = true, shift = true)),
}

public fun Settings.combo(shortcut: AppShortcut): KeyCombo? =
    shortcuts[shortcut.name]?.let { if (it.isEmpty()) null else KeyCombo.decode(it) } ?: shortcut.default.takeUnless { shortcut.name in shortcuts }
