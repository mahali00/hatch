package dev.mahali.hatch.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Read it while drawing: a step then redraws only. Stops when the app leaves STARTED. */
@Composable
public fun rememberStep(periodMillis: Long, count: Int): State<Int> {
    val step = remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, periodMillis, count) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(periodMillis)
                step.intValue = (step.intValue + 1) % count
            }
        }
    }
    return step
}

@Composable
public fun HatchSpinner(modifier: Modifier = Modifier) {
    val step = rememberStep(SPINNER_STEP_MILLIS, SPINNER_STEPS)
    val track = chrome.stroke
    val arc = chrome.accent
    Canvas(modifier.size(18.dp).padding(1.5.dp)) {
        val width = 1.8.dp.toPx()
        val inset = width / 2
        val box = Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(width))
        drawArc(arc, step.value * 360f / SPINNER_STEPS - 90f, 90f, false, Offset(inset, inset), box, style = Stroke(width, cap = StrokeCap.Round))
    }
}

private const val SPINNER_STEPS = 8
private const val SPINNER_STEP_MILLIS = 250L
