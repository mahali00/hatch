package dev.mahali.hatch

import android.view.KeyEvent
import dev.mahali.hatch.core.KeyCombo
import dev.mahali.hatch.design.ShortcutRecording

// The activity sees every key before a focused view does, so a terminal can't swallow ⌘T.
internal object Shortcuts {
    @Volatile var handler: ((KeyCombo) -> Boolean)? = null

    fun handle(event: KeyEvent): Boolean {
        if (ShortcutRecording.active || KeyEvent.isModifierKey(event.keyCode)) return false
        if (!event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed) return false
        return handler?.invoke(KeyCombo(event.keyCode, event.isMetaPressed, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)) ?: false
    }
}
