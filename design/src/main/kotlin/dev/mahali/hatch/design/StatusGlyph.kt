package dev.mahali.hatch.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.StatusMark
import dev.mahali.hatch.core.BlockedKind

@Composable
public fun StatusGlyph(mark: StatusMark, progress: Int?, showDone: Boolean, modifier: Modifier = Modifier, kind: BlockedKind? = null) {
    val shown = if (mark == StatusMark.Done && !showDone) StatusMark.None else mark
    val description = statusDescription(shown, progress, kind) ?: return
    val semantics = modifier.semantics { contentDescription = description }
    when {
        shown == StatusMark.Blocked -> Row(semantics, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(HatchIcons.Alert, null, Modifier.size(18.dp), tint = chrome.warning)
            progress?.let { ProgressRing(it, Modifier) }
        }
        progress != null && progress >= 0 && (shown == StatusMark.Working || shown == StatusMark.None) -> ProgressRing(progress, semantics)
        shown == StatusMark.Working -> WorkingDots(semantics)
        shown == StatusMark.Done -> Dot(chrome.success, semantics)
        shown == StatusMark.Error -> Dot(chrome.danger, semantics)
        progress == -1 -> WorkingDots(semantics)
    }
}

public fun statusDescription(mark: StatusMark, progress: Int?, kind: BlockedKind? = null): String? {
    val label = when (mark) {
        StatusMark.Blocked -> kind?.label ?: "Needs input"
        StatusMark.Working -> "Working"
        StatusMark.Done -> "Done"
        StatusMark.Error -> "Error"
        StatusMark.None -> if (progress == -1) "Working" else null
    }
    val percent = progress?.takeIf { it in 0..100 && (mark == StatusMark.Working || mark == StatusMark.Blocked || mark == StatusMark.None) }
    return listOfNotNull(label, percent?.let { "$it percent" }).takeIf { it.isNotEmpty() }?.joinToString(", ")
}

@Composable
private fun Dot(color: Color, modifier: Modifier) {
    Box(modifier.size(9.dp).background(color, CircleShape))
}

/** Steps rather than animates: a redraw of this row 2.5 times a second, not the whole window per frame. */
@Composable
private fun WorkingDots(modifier: Modifier) {
    val step = rememberStep(DOT_MILLIS, 3)
    val bright = chrome.text
    val dim = chrome.secondary
    // The step is read only while drawing, so a step redraws without recomposing.
    Canvas(modifier.size(22.dp, 6.dp)) {
        val lead = step.value
        val radius = 2.6.dp.toPx()
        for (i in 0..2) {
            val color = when (i) {
                lead -> bright
                (lead + 2) % 3 -> dim
                else -> dim.copy(alpha = dim.alpha * 0.5f)
            }
            drawCircle(color, radius, Offset((3 + i * 8).dp.toPx(), size.height / 2))
        }
    }
}

private const val DOT_MILLIS = 400L

@Composable
private fun ProgressRing(progress: Int, modifier: Modifier) {
    val track = chrome.divider
    val fill = chrome.accent
    Canvas(modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        val radius = 6.dp.toPx()
        val topLeft = Offset(center.x - radius, center.y - radius)
        drawCircle(track, radius, style = Stroke(stroke))
        drawArc(
            fill, startAngle = -90f, sweepAngle = 3.6f * progress.coerceIn(0, 100), useCenter = false,
            topLeft = topLeft, size = Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
