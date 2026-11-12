package dev.mahali.hatch.feature.terminal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.engine.Pane
import dev.mahali.hatch.ghostty.ScreenCell
import dev.mahali.hatch.renderer.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The fade is read in draw only: one small layer per frame, never layout or the terminal.
internal class CopyFlash(private val scope: CoroutineScope) {
    var range: Pair<ScreenCell, ScreenCell>? by mutableStateOf(null)
        private set
    val strength = Animatable(0f)

    fun flash(cells: Pair<ScreenCell, ScreenCell>?) {
        range = cells ?: return
        scope.launch {
            strength.snapTo(1f)
            strength.animateTo(0f, tween(FADE_MILLIS, delayMillis = HOLD_MILLIS, easing = LinearOutSlowInEasing))
            range = null
        }
    }
}

@Composable
internal fun BoxScope.CopyFlashLayer(flash: CopyFlash, pane: Pane, view: TerminalView) {
    val range = flash.range ?: return
    val (a, b) = if (range.first <= range.second) range else range.second to range.first
    val color = chrome.accent
    Canvas(Modifier.matchParentSize().graphicsLayer { alpha = flash.strength.value * FLASH_ALPHA }) {
        val g = pane.geometry
        val top = g.scroll.offset
        val cols = (g.displayed?.cols ?: 0).coerceAtLeast(1)
        val rows = g.scroll.rows
        for (y in a.y..b.y) {
            val row = y - top
            if (row !in 0 until rows) continue
            val from = if (y == a.y) a.x else 0
            val to = if (y == b.y) b.x else cols - 1
            val left = view.cellLeft(from)
            val rowTop = view.cellTop(row)
            drawRect(color, Offset(left, rowTop), Size(view.cellLeft(to + 1) - left, view.cellTop(row + 1) - rowTop))
        }
    }
}

private const val HOLD_MILLIS = 120
private const val FADE_MILLIS = 380
private const val FLASH_ALPHA = 0.6f
