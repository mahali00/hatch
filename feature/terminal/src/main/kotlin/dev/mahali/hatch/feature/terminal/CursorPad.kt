package dev.mahali.hatch.feature.terminal

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.chrome
import kotlin.math.abs

@Composable
internal fun CursorPad(cellWidth: Int, cellHeight: Int, onArrow: (Arrow) -> Unit, onTouch: () -> Unit, modifier: Modifier = Modifier) {
    var direction by remember { mutableStateOf<Direction?>(null) }
    var active by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(if (active) 1f else 0f,
        animationSpec = tween(160, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)), label = "joystick")
    val sendArrow by rememberUpdatedState(onArrow)
    val touchFeedback by rememberUpdatedState(onTouch)
    val accent = chrome.accent.copy(alpha = 0.75f)
    val face = chrome.text.copy(alpha = 0.09f).compositeOver(chrome.card)
    val hub = chrome.terminal
    val text = chrome.text

    Box(
        modifier.size(PadSize).semantics { contentDescription = "Cursor pad" }.pointerInput(cellWidth, cellHeight) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                active = true
                touchFeedback()
                val travel = Trackpad(cellWidth.toFloat(), cellHeight.toFloat())
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val moved = change.positionChange()
                        change.consume()
                        val step = travel.add(moved.x, moved.y)
                        if (step.isNotEmpty()) {
                            step.forEach(sendArrow)
                            direction = joystickDirection(moved.x, moved.y, 0f)
                        }
                    }
                } finally {
                    active = false
                    direction = null
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(KeyIcons.CursorPad, null, Modifier.size(18.dp).graphicsLayer { alpha = 1f - reveal }, tint = text)
        Canvas(Modifier.size(PadSize).graphicsLayer { alpha = reveal }) {
            drawCircle(face)
            direction?.let { d -> drawArc(accent, d.degrees - WEDGE / 2, WEDGE, useCenter = true) }
            drawCircle(hub, radius = this.size.minDimension * 0.15f)
        }
    }
}

private val PadSize = 40.dp
private const val WEDGE = 90f

// Keeps the remainder so slow drags still move; caps steps per event so a fast swipe doesn't flood the host.
internal class Trackpad(cellWidth: Float, cellHeight: Float) {
    private val columns = LineAccumulator(cellWidth.coerceAtLeast(1f))
    private val rows = LineAccumulator(cellHeight.coerceAtLeast(1f))

    fun add(dx: Float, dy: Float): List<Arrow> {
        val x = columns.add(dx).coerceIn(-MAX_STEP, MAX_STEP)
        val y = rows.add(dy).coerceIn(-MAX_STEP, MAX_STEP)
        return List(abs(x)) { if (x > 0) Arrow.Right else Arrow.Left } + List(abs(y)) { if (y > 0) Arrow.Down else Arrow.Up }
    }
}

private const val MAX_STEP = 4
