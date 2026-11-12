package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.lerp
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.chrome

// Two-finger pan wins over a divider drag.
internal class CanvasGestures { var panning: Boolean = false }

@Composable
internal fun PaneCanvas(stage: TerminalViewport, scrollable: Boolean, gestures: CanvasGestures, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var overview by remember { mutableStateOf(false) }
    SideEffect { if (!scrollable) gestures.panning = false }
    // A reveal scrolls to the pane rather than jumping; a newer reveal takes over, and a finger stops it.
    DisposableEffect(stage) {
        stage.animatesReveals = true
        onDispose { stage.animatesReveals = false }
    }
    val motion = Hatch.motion
    LaunchedEffect(stage, motion) {
        snapshotFlow { stage.revealTarget }.collectLatest { target ->
            if (target == null) return@collectLatest
            val from = stage.scroll
            animate(0f, 1f, animationSpec = motion.spec(motion.standard)) { f, _ -> stage.scrollTo(lerp(from, target, f)) }
            stage.revealArrived(target)
        }
    }
    val pan = if (!scrollable) Modifier else Modifier.pointerInput(stage) {
      try {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gestures.panning = false
            var panning = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    panning = true
                    gestures.panning = true
                }
                if (panning) {
                    if (event.changes.count { it.pressed } >= 2) {
                        val delta = event.calculatePan()
                        stage.scrollBy(-delta.x, 0f)
                        if (delta != Offset.Zero) overview = true
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            overview = false
        }
      } finally { overview = false }
    }.semantics {
        contentDescription = "Scrollable columns. Use two fingers to move horizontally between columns."
        horizontalScrollAxisRange = ScrollAxisRange({ stage.scroll.x }, { (stage.canvas.width - stage.size.width).coerceAtLeast(0).toFloat() })
        scrollBy { x, _ -> stage.scrollBy(x, 0f); true }
    }
    Box(modifier.clipToBounds()) {
        Box(Modifier.fillMaxSize().then(pan)) { content() }
        if (scrollable) AnimatedVisibility(overview, Modifier.align(Alignment.TopEnd).padding(Hatch.space.m),
            enter = fadeIn(tween(80)), exit = fadeOut(tween(200)),
        ) {
            val stroke = chrome.secondary
            val accent = chrome.accent
            val fill = chrome.card
            Canvas(Modifier.size(66.dp, 48.dp).clip(HatchShapes.Card).background(fill).padding(Hatch.space.s)) {
                val line = 1.dp.toPx()
                val canvas = stage.canvas
                if (canvas.width <= 0 || canvas.height <= 0) return@Canvas
                val sx = size.width / canvas.width
                val sy = size.height / canvas.height
                for (p in stage.placed.value) {
                    drawRect(stroke, Offset(p.rect.left * sx, p.rect.top * sy), Size(p.rect.width * sx, p.rect.height * sy), style = Stroke(line))
                }
                val at = Offset(stage.scroll.x * sx, stage.scroll.y * sy)
                val window = Size(minOf(stage.size.width, canvas.width) * sx, minOf(stage.size.height, canvas.height) * sy)
                drawRect(accent.copy(alpha = 0.12f), at, window)
                drawRect(accent, at, window, style = Stroke(line))
            }
        }
    }
}
