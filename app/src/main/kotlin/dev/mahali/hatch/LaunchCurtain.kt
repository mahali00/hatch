package dev.mahali.hatch

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.HatchBackground
import dev.mahali.hatch.design.R as DesignR
import kotlin.math.max

/** Arrival, blink and expanding silhouette measured from the 60fps launch reference. */
@Composable
internal fun LaunchCurtain(start: Boolean, onDone: () -> Unit) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    val timeline = remember { Animatable(if (motion) 0f else TOTAL_MILLIS.toFloat()) }
    val mascot = ImageBitmap.imageResource(DesignR.drawable.hatch_mascot)
    val eyelids = ImageBitmap.imageResource(DesignR.drawable.hatch_blink)
    LaunchedEffect(start) {
        if (!start) return@LaunchedEffect
        withFrameNanos { }
        if (motion) timeline.animateTo(TOTAL_MILLIS.toFloat(), tween(TOTAL_MILLIS, easing = LinearEasing))
        onDone()
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val entrance = EaseOut.transform((timeline.value / ENTRANCE_MILLIS).coerceIn(0f, 1f))
        val elapsed = (timeline.value - ENTRANCE_MILLIS).coerceIn(0f, INTRO_MILLIS.toFloat())
        val reveal = ((timeline.value - ENTRANCE_MILLIS - INTRO_MILLIS) / REVEAL_MILLIS).coerceIn(0f, 1f)
        val arrival = EaseOut.transform((elapsed / 140f).coerceIn(0f, 1f))
        val settle = when {
            elapsed < 200f -> 0.94f + 0.10f * EaseOut.transform(elapsed / 200f)
            elapsed < 350f -> 1.04f
            else -> 1.04f - 0.14f * EaseInOut.transform(((elapsed - 350f) / (INTRO_MILLIS - 350f)).coerceIn(0f, 1f))
        }
        val blink = when {
            elapsed < 330f -> 0f
            elapsed < 375f -> (elapsed - 330f) / 45f
            elapsed < 435f -> 1f
            elapsed < 480f -> 1f - (elapsed - 435f) / 45f
            else -> 0f
        }
        val handoff = EaseInOut.transform((reveal * REVEAL_MILLIS / 80f).coerceIn(0f, 1f))
        val mark = 144.dp.toPx()
        // The mascot's alpha contains an opaque circle of radius 0.28 of its canvas.
        val cover = max(1f, center.getDistance() / (mark * 0.9f * 0.28f) * 1.05f)
        val sample = reveal * (RevealScales.size - 1)
        val index = sample.toInt().coerceAtMost(RevealScales.lastIndex - 1)
        val measured = RevealScales[index] + (RevealScales[index + 1] - RevealScales[index]) * (sample - index)
        val scale = 1f + (cover - 1f) * (measured - 1f) / 21f

        drawRect(HatchBackground)
        val entranceScale = 0.92f + 0.08f * entrance
        val card = Size(size.width * entranceScale, size.height * entranceScale)
        drawRoundRect(
            Brush.linearGradient(
                0f to Color(0xFFF4E8D5), 0.22f to Color(0xFF9ADBC5),
                0.5f to LaunchMiddle, 1f to HatchBackground,
                start = Offset(size.width, 0f), end = Offset(0f, size.height),
            ),
            topLeft = Offset((size.width - card.width) / 2f, (size.height - card.height) / 2f),
            size = card,
            cornerRadius = CornerRadius(28.dp.toPx() * (1f - entrance)),
            alpha = entrance,
        )
        withTransform({
            translate(center.x, center.y)
            scale(mark * settle * scale / mascot.width, mark * settle * scale / mascot.height, pivot = Offset.Zero)
            translate(-mascot.width / 2f, -mascot.height / 2f)
        }) {
            drawImage(mascot, alpha = arrival * (1f - handoff))
            if (blink > 0f) drawImage(eyelids, alpha = arrival * blink * (1f - handoff))
            if (handoff > 0f) drawImage(mascot, alpha = handoff, blendMode = BlendMode.DstOut)
        }
    }
}

private val LaunchMiddle = Color(0xFF398D7A)
private val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
private const val ENTRANCE_MILLIS = 220
private const val INTRO_MILLIS = 680
private const val REVEAL_MILLIS = 400
private const val TOTAL_MILLIS = ENTRANCE_MILLIS + INTRO_MILLIS + REVEAL_MILLIS

// One scale sample per reference frame, from the eye-to-cutout handoff through full reveal.
private val RevealScales = floatArrayOf(
    1f, 1f, 1.01f, 1.02f, 1.05f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f,
    1.65f, 1.85f, 2.1f, 2.45f, 2.85f, 3.35f, 4f, 4.85f, 5.9f,
    7.35f, 9.1f, 11.4f, 14.3f, 18f, 22f,
)
