package dev.mahali.hatch.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Masks the terminal surface's corners after drawing, so the terminal needs no padding. */
public fun Modifier.paneFrame(canvas: Color, outline: Color, cornerRadius: Dp): Modifier = drawWithCache {
    val radius = minOf(cornerRadius.toPx(), size.minDimension / 2f)
    val corners = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
    }
    val stroke = 1f.coerceAtMost(size.minDimension)
    val inset = stroke / 2f
    onDrawWithContent {
        drawContent()
        drawPath(corners, canvas)
        drawRoundRect(
            outline,
            topLeft = Offset(inset, inset),
            size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}
