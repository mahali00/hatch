package dev.mahali.hatch.design

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.KeyCombo

/** Set while a [HatchShortcutChip] records, so the app's own shortcuts let the keys through to it. */
public object ShortcutRecording {
    public var active: Boolean by mutableStateOf(false)
}

@Composable
public fun HatchShortcutChip(combo: KeyCombo?, onRecord: (KeyCombo) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var recording by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(recording) {
        ShortcutRecording.active = recording
        if (recording) focus.requestFocus()
    }
    Box(
        modifier.heightIn(min = Hatch.size.controlCompact).clip(HatchShapes.Capsule)
            .background(if (recording) chrome.accentContainer else chrome.control)
            .then(if (recording) Modifier.border(1.dp, chrome.accent, HatchShapes.Capsule) else Modifier)
            .focusRequester(focus).onFocusChanged { if (!it.isFocused) recording = false }.focusable()
            .onPreviewKeyEvent { event ->
                if (!recording || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                val code = native.keyCode
                val modified = native.isMetaPressed || native.isCtrlPressed || native.isAltPressed
                when {
                    code == KeyEvent.KEYCODE_ESCAPE -> recording = false
                    code == KeyEvent.KEYCODE_DEL && !modified -> { onClear(); recording = false }
                    KeyEvent.isModifierKey(code) -> Unit // wait for the key itself
                    modified -> {
                        onRecord(KeyCombo(code, native.isMetaPressed, native.isCtrlPressed, native.isAltPressed, native.isShiftPressed))
                        recording = false
                    }
                }
                true
            }
            .clickable(role = Role.Button) { recording = !recording }
            .padding(horizontal = Hatch.space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (recording) "Type shortcut" else combo?.label ?: "Record Shortcut",
            style = Hatch.type.callout, color = if (recording || combo != null) chrome.text else chrome.secondary, maxLines = 1,
        )
    }
}
