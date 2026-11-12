package dev.mahali.hatch.feature.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

internal fun Modifier.paneTitleGestures(
    vararg keys: Any,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onMenu: () -> Unit,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: (Boolean) -> Unit,
): Modifier = semantics {
    onClick("Select pane") { onTap(); true }
    onLongClick("Pane actions") { onMenu(); true }
}.pointerInput(*keys) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val slop = viewConfiguration.touchSlop
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result: Boolean? = null
            while (result == null) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                result = when {
                    change == null || change.isConsumed || event.changes.count { it.pressed } > 1 -> false
                    !change.pressed -> true
                    (change.position - down.position).getDistance() > slop -> false
                    else -> null
                }
            }
            result
        }
        if (released != null) {
            if (released) onTap()
            return@awaitEachGesture
        }
        onHold()
        var dragging = false
        // Every way out of a hold calls onEnd exactly once.
        var ended = false
        try {
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                // Taken away (second finger, system): end the hold even if never dragged.
                ended = true
                dragging = false
                onEnd(true)
                break
            }
            if (!change.pressed) {
                ended = true
                if (dragging) { dragging = false; onEnd(false) } else { onEnd(true); onMenu() }
                break
            }
            if (!dragging && (change.position - down.position).getDistance() > slop) {
                dragging = true
                onStart(down.position)
            }
            if (dragging) {
                change.consume()
                onMove(change.position)
            }
          }
        } finally {
            if (!ended) onEnd(true)
        }
    }
}
