package dev.mahali.hatch.feature.sessions

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.mahali.hatch.core.WindowRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The pane title strip's hold, for a tab row: a hold picks the row up, a release without moving opens its menu, and moving
 * past touch slop drags it. Taps stay with the row's own click, and a swipe before the hold still scrolls the list.
 */
internal fun Modifier.holdToReorder(
    key: Any,
    onHold: (downY: Float) -> Unit,
    onMenu: () -> Unit,
    onDrag: (dy: Float) -> Unit,
    onEnd: (cancelled: Boolean) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        // The row's click takes the down; this only watches it.
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val letGo = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed || !change.pressed || event.changes.count { it.pressed } > 1 ||
                    (change.position - down.position).getDistance() > slop
                ) break
            }
        }
        if (letGo != null) return@awaitEachGesture
        onHold(down.position.y)
        var dragging = false
        // Every way out of a hold calls onEnd exactly once.
        var ended = false
        try {
            while (true) {
                // Taken on the Initial pass: the row's click below never fires, and the list's scroll and the sidebar's swipe above see it used.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                    ended = true
                    onEnd(true)
                    break
                }
                change.consume()
                if (!change.pressed) {
                    ended = true
                    onEnd(!dragging)
                    if (!dragging) onMenu()
                    break
                }
                if (!dragging && (change.position - down.position).getDistance() > slop) dragging = true
                if (dragging) onDrag(change.position.y - down.position.y)
            }
        } finally {
            if (!ended) onEnd(true)
        }
    }
}

/** One card's tab drag: the held row follows the finger, the rows it passes step aside, and a drop places the tab. */
@Stable
internal class TabDrag(private val scope: CoroutineScope, private val view: View) {
    // Kept current by the card on each composition.
    var rows: List<WindowRef> = emptyList()
    var spec: AnimationSpec<Float> = snap()
    var haptics: Boolean = false
    var place: (from: Int, to: Int) -> Unit = { _, _ -> }
    var rowHeight: Float = 0f

    var held: WindowRef? by mutableStateOf(null)
        private set
    var dragging: Boolean by mutableStateOf(false)
        private set
    var from: Int = 0
        private set

    /** The finger's y in the held row's resting box, and its travel since the press. */
    var fingerY: Float = 0f
        private set
    var dragY: Float = 0f
        private set
    private var order = emptyList<WindowRef>()
    private var target: Int? = null
    private val offsets = mutableStateMapOf<WindowRef, Float>()
    private val slides = HashMap<WindowRef, Job>()

    fun offset(ref: WindowRef): Float = offsets[ref] ?: 0f

    fun hold(ref: WindowRef, downY: Float) {
        if (held != null) return
        val at = rows.indexOf(ref).takeIf { it >= 0 } ?: return
        held = ref
        order = rows
        from = at
        target = at
        fingerY = downY
        dragY = 0f
        dragging = false
        // LONG_PRESS, as the pane strip: DRAG_START is barely felt on Samsung phones.
        buzz(HapticFeedbackConstants.LONG_PRESS)
    }

    fun dragTo(dy: Float) {
        val ref = held ?: return
        dragging = true
        dragY = dy
        set(ref, dy)
        val to = reorderTarget(from, dy, rowHeight, order.size)
        if (to == target) return
        target = to
        order.forEachIndexed { i, other -> if (other != ref) slide(other, reorderShift(i, from, to) * rowHeight) }
        if (to != null) buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    fun end(cancelled: Boolean) {
        val ref = held ?: return
        val to = target
        held = null
        dragging = false
        target = null
        slides.values.forEach { it.cancel() }
        slides.clear()
        // Rows that changed under the hold (a tab came or went) snap back rather than guess.
        if (!cancelled && to != null && to != from && rows == order) {
            // The rows take their new places in this same frame, so only the held row has distance left to settle.
            offsets.clear()
            offsets[ref] = dragY - (to - from) * rowHeight
            place(from, to)
            buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
        } else {
            order.forEach { if (it != ref) slide(it, 0f) }
        }
        slide(ref, 0f)
    }

    private fun set(ref: WindowRef, value: Float) {
        slides.remove(ref)?.cancel()
        offsets[ref] = value
    }

    private fun slide(ref: WindowRef, to: Float) {
        slides.remove(ref)?.cancel()
        val start = offsets[ref] ?: 0f
        if (start == to) return
        slides[ref] = scope.launch { animate(start, to, animationSpec = spec) { value, _ -> offsets[ref] = value } }
    }

    private fun buzz(constant: Int) {
        if (haptics) view.performHapticFeedback(constant)
    }
}
