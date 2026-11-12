package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.IntSize
import kotlin.math.sqrt

// 4096 caps the texture side; maxPixels caps the bitmap allocation.
internal fun pdfPageSize(width: Int, height: Int, targetWidth: Int, maxPixels: Int): IntSize {
    require(width > 0 && height > 0 && targetWidth > 0 && maxPixels > 0)
    val scale = minOf(
        targetWidth.toDouble() / width,
        4096.0 / maxOf(width, height),
        sqrt(maxPixels.toDouble() / width / height),
    )
    val w = (width * scale).toInt().coerceIn(1, minOf(4096, maxPixels))
    val h = (height * scale).toInt().coerceIn(1, minOf(4096, maxPixels / w))
    return IntSize(w, h)
}
