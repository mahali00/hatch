package dev.mahali.hatch.feature.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.rects
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.floatingEdge
import kotlin.math.floor
import kotlin.math.min

@Composable
internal fun Minimap(layout: Layout, pages: List<BlockId>, position: () -> Float, modifier: Modifier = Modifier) {
    // Every tiled pane's rectangle, then each page's (NaN for a floating pane), as x, y, w, h runs.
    val all = remember(layout) { layout.rects().values.flatMap { listOf(it.x, it.y, it.width, it.height) }.toFloatArray() }
    val byPage = remember(layout, pages) {
        val rects = layout.rects()
        FloatArray(pages.size * 4) { i ->
            val r = rects[pages[i / 4]] ?: return@FloatArray Float.NaN
            when (i % 4) { 0 -> r.x; 1 -> r.y; 2 -> r.width; else -> r.height }
        }
    }
    val accent = chrome.accent
    val other = chrome.stroke
    val shape = HatchShapes.Small
    Canvas(modifier.size(77.dp, 52.dp).clip(shape).background(chrome.card).floatingEdge(shape).padding(6.dp)) {
        val gap = 1.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        var i = 0
        while (i < all.size) { rect(other, all[i], all[i + 1], all[i + 2], all[i + 3], gap, radius); i += 4 }
        if (pages.isEmpty()) return@Canvas
        val p = position().coerceIn(0f, (pages.size - 1).toFloat())
        var a = floor(p).toInt()
        var b = min(a + 1, pages.size - 1)
        val f = p - a
        if (byPage[a * 4].isNaN()) a = b
        if (byPage[b * 4].isNaN()) b = a
        if (byPage[a * 4].isNaN()) return@Canvas
        rect(
            accent,
            lerp(byPage[a * 4], byPage[b * 4], f), lerp(byPage[a * 4 + 1], byPage[b * 4 + 1], f),
            lerp(byPage[a * 4 + 2], byPage[b * 4 + 2], f), lerp(byPage[a * 4 + 3], byPage[b * 4 + 3], f),
            gap, radius,
        )
    }
}

private fun DrawScope.rect(color: Color, x: Float, y: Float, w: Float, h: Float, gap: Float, radius: CornerRadius) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x * size.width + gap, y * size.height + gap),
        size = Size(w * size.width - gap * 2, h * size.height - gap * 2),
        cornerRadius = radius,
    )
}
