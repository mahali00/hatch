package dev.mahali.hatch.feature.terminal

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.util.VelocityTracker
import dev.mahali.hatch.engine.Pane
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.engine.namedKey
import dev.mahali.hatch.renderer.TerminalView
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun Modifier.paneGestures(
    rex: Rex,
    ref: PaneRef,
    pane: Pane,
    view: () -> TerminalView?,
    smoothScrolling: Boolean,
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
): Modifier = this
    .pointerInput(pane) { detectTapGestures(onTap = onTap, onLongPress = onLongPress) }
    // Wheel and trackpad: three lines a notch, as Termux does, through the same modes as a drag.
    .pointerInput(pane) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val n = -(change.scrollDelta.y * WHEEL_LINES).roundToInt()
                if (n == 0) continue
                change.consume()
                val modes = pane.geometry.modes
                when (scrollMode(modes.alternateScreen, modes.mouseTracking, modes.alternateScroll)) {
                    ScrollMode.History -> pane.scroll(-n)
                    ScrollMode.Wheel -> view()?.let { v ->
                        rex.wheel(ref.host, ref.session, ref.block, v.columnAt(change.position.x), v.rowAt(change.position.y).coerceAtLeast(0), -n)
                    }
                    ScrollMode.Arrows -> repeat(kotlin.math.abs(n)) {
                        rex.key(ref.host, ref.session, ref.block, namedKey(if (n > 0) "arrow_up" else "arrow_down"))
                    }
                    ScrollMode.None -> Unit
                }
            }
        }
    }
    .pointerInput(pane, smoothScrolling) {
        val decay = splineBasedDecay<Float>(this)
        coroutineScope {
            var fling: Job? = null
            var mode = ScrollMode.History
            var lineHeight = 40f
            var lines = LineAccumulator(lineHeight)
            var col = 0
            var row = 0
            val tracker = VelocityTracker()
            // History scrolls by the pixel, as a list does; the other modes only take whole lines.
            fun smooth() = smoothScrolling && mode == ScrollMode.History
            // A flick keeps going after the finger lifts, in history and in programs that scroll themselves.
            fun momentum() = smoothScrolling && mode != ScrollMode.None

            // Positive [dy] is the finger moving down: the content follows it, showing older lines.
            fun scroll(dy: Float) {
                if (smooth()) return pane.scrollPixels(dy, lineHeight)
                val n = lines.add(dy)
                if (n == 0) return
                when (mode) {
                    ScrollMode.History -> pane.scroll(-n)
                    ScrollMode.Wheel -> rex.wheel(ref.host, ref.session, ref.block, col, row, -n)
                    ScrollMode.Arrows -> repeat(kotlin.math.abs(n)) {
                        rex.key(ref.host, ref.session, ref.block, namedKey(if (n > 0) "arrow_up" else "arrow_down"))
                    }
                    ScrollMode.None -> Unit
                }
            }

            suspend fun settle() {
                val shift = pane.scrollShift
                if (shift > 0f) {
                    var last = 0f
                    animate(0f, if (shift >= lineHeight / 2) lineHeight - shift else -shift, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                        pane.scrollPixels(value - last, lineHeight)
                        last = value
                    }
                }
                pane.settleScroll(lineHeight)
            }

            var dragging = false

            // A touch stops a fling (or a settle) where it is, as in any list. A drag carries on from
            // there; lifting without one rests on the nearest line.
            launch {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when {
                            event.type == PointerEventType.Press -> fling?.cancel()
                            event.type == PointerEventType.Release && !dragging && event.changes.none { it.pressed } &&
                                pane.scrollShift > 0f -> fling = launch { settle() }
                        }
                    }
                }
            }

            try {
                detectVerticalDragGestures(
                    onDragStart = { at ->
                        fling?.cancel()
                        dragging = true
                        tracker.resetTracking()
                        mode = pane.geometry.modes.let { scrollMode(it.alternateScreen, it.mouseTracking, it.alternateScroll) }
                        val v = view()
                        lineHeight = (v?.cell?.height ?: 40).toFloat()
                        lines = LineAccumulator(lineHeight)
                        col = v?.columnAt(at.x) ?: 0
                        row = (v?.rowAt(at.y) ?: 0).coerceAtLeast(0)
                    },
                    onDragEnd = {
                        dragging = false
                        val velocity = tracker.calculateVelocity().y
                        if (momentum()) fling = launch {
                            var last = 0f
                            AnimationState(0f, velocity).animateDecay(decay) {
                                scroll(value - last)
                                last = value
                            }
                            if (smooth()) settle()
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        if (smooth()) fling = launch { settle() }
                    },
                ) { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                    scroll(dy)
                }
            } finally {
                pane.settleScroll(lineHeight)
            }
        }
    }

private const val WHEEL_LINES = 3f
