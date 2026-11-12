package dev.mahali.hatch.renderer

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

public data class TerminalFont(
    val regular: Typeface,
    val bold: Typeface = Typeface.create(regular, Typeface.BOLD),
    val italic: Typeface = Typeface.create(regular, Typeface.ITALIC),
    val boldItalic: Typeface = Typeface.create(regular, Typeface.BOLD_ITALIC),
    val sizePx: Float,
    val lineSpacing: Float = 1f,
) {
    internal fun face(isBold: Boolean, isItalic: Boolean): Typeface = when {
        isBold && isItalic -> boldItalic
        isBold -> bold
        isItalic -> italic
        else -> regular
    }
}

public data class CellMetrics(val width: Int, val height: Int, val baseline: Int) {
    public companion object {
        public fun of(font: TerminalFont): CellMetrics {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.regular; textSize = font.sizePx }
            val fm = paint.fontMetrics
            val width = ceil(paint.measureText("M")).toInt().coerceAtLeast(1)
            val natural = fm.descent - fm.ascent
            val height = ceil(natural * font.lineSpacing).toInt().coerceAtLeast(1)
            return CellMetrics(width, height, ceil(-fm.ascent + (height - natural) / 2).toInt())
        }
    }
}
