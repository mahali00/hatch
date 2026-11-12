package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.HatchScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchIcons.circle

// Vectors, not font glyphs: device fonts differ in which of these symbols they cover.
internal object KeyIcons {
    // Lucide paths (ISC licence), 24-unit grid, round stroke.
    val Command: ImageVector = key("M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3")
    val Control: ImageVector = key("M18 15l-6-6-6 6")
    val Option: ImageVector = key("M3 3h6l6 18h6", "M14 3h7")
    val Shift: ImageVector = key("M9 18v-6H5l7-7 7 7h-4v6H9Z")
    val Tab: ImageVector = key("M17 12H3", "M11 18l6-6-6-6", "M21 5v14")
    val CursorPad: ImageVector = key(
        circle(12f, 12f, 9.5f),
        filled = listOf(circle(12f, 12f, 2.6f), "M12 4.6l-1.8 2.2h3.6ZM12 19.4l-1.8-2.2h3.6ZM4.6 12l2.2-1.8v3.6ZM19.4 12l-2.2-1.8v3.6Z"),
    )
    val HideKeyboard: ImageVector = key(
        "M5 4h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z",
        "M7 8h.01M11 8h.01M15 8h.01M8 11.5h8M9 19l3 2 3-2",
    )

    // Crops the grid's 2-unit margin so the ink is as large as iOS draws it.
    private fun key(vararg paths: String, filled: List<String> = emptyList()): ImageVector {
        val size = HatchScale.Phone.size.keyBarIcon
        val crop = 24f - 2 * MARGIN
        val stroke = HatchIcons.STROKE_DP * crop / size.value
        return ImageVector.Builder(defaultWidth = size, defaultHeight = size, viewportWidth = crop, viewportHeight = crop).apply {
            addGroup(translationX = -MARGIN, translationY = -MARGIN)
            for (d in paths) addPath(
                addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
            for (d in filled) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
            clearGroup()
        }.build()
    }

    private const val MARGIN = 2f
}
