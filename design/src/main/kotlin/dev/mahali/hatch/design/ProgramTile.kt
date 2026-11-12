package dev.mahali.hatch.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.ProgramKey

/** Proportions follow the iOS app's tile. Logo-less programs get the terminal's `>_`, never a letter. */
@Composable
public fun ProgramTile(program: ProgramKey, size: Dp, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val height = size * TILE_ASPECT
    val frame = TileFrame(
        radius = size * 0.2f,
        edge = size * (0.6f / 30f),
        rim = size * (1.1f / 30f),
        edgeColor = if (chrome.dark) Color.Transparent else Color.Black.copy(alpha = 0.15f),
        rimColor = if (chrome.dark) Color.White.copy(alpha = 0.14f) else Color.White,
    )
    Box(modifier.size(size, height)) {
        if (stacked) {
            Box(Modifier.align(Alignment.TopCenter).offset(y = -size * 0.13f).size(size * 0.84f, height).tile(frame, PeekLayer))
        }
        when (val icon = iconOf(program)) {
            is ProgramIcon.Logo -> Box(Modifier.fillMaxSize().tile(frame, icon.background), contentAlignment = Alignment.Center) {
                Image(painterResource(icon.drawable), contentDescription = null, modifier = Modifier.size(height * minOf(0.68f, icon.scale * 1.15f)))
            }
            ProgramIcon.Generic -> Box(Modifier.fillMaxSize().tile(frame, GenericTile), contentAlignment = Alignment.Center) {
                Prompt(GenericGlyph, Modifier.size(size * 0.43f, height * 0.48f))
            }
        }
    }
}

private const val TILE_ASPECT = 23f / 30f
private val PeekLayer = Color(0xFF3A3B3F)
private val GenericTile = Color(0xFF2F3135)
private val GenericGlyph = Color(0xFF98E491)

/** Ring widths are the iOS tile's, at a 30 pt tile: 0.6 pt edge, 1.1 pt rim. */
private class TileFrame(val radius: Dp, val edge: Dp, val rim: Dp, val edgeColor: Color, val rimColor: Color)

/** Logo backgrounds are clipped to the inner rim, so their square corners never show. */
private fun Modifier.tile(frame: TileFrame, fill: Color): Modifier = drawWithContent {
    val r = frame.radius.toPx()
    val e = frame.edge.toPx()
    val inner = e + frame.rim.toPx()
    fun ring(inset: Float) = Path().apply {
        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(maxOf(r - inset, 0f))))
    }
    val rim = frame.rimColor.compositeOver(fill)
    drawPath(ring(0f), frame.edgeColor.compositeOver(rim))
    drawPath(ring(e), rim)
    clipPath(ring(inner)) {
        drawRect(fill)
        this@drawWithContent.drawContent()
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.45f to Color.Transparent))
    }
}

@Composable
private fun Prompt(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 14f
        val v = size.height / 10f
        val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(1 * u, 1 * v); lineTo(5 * u, 5 * v); lineTo(1 * u, 9 * v) }, tint, style = stroke)
        drawLine(tint, Offset(7 * u, 9 * v), Offset(13 * u, 9 * v), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
    }
}

@Composable
public fun ProgramGlyph(program: ProgramKey, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (val icon = iconOf(program)) {
            is ProgramIcon.Logo -> if (program.value in shellKeys) {
                Prompt(tint, Modifier.size(size * (14f / 18f), size * (10f / 18f)))
            } else Image(
                painterResource(icon.drawable), contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter = if (icon.tintable) ColorFilter.tint(tint) else null,
            )
            ProgramIcon.Generic -> Prompt(tint, Modifier.size(size * (14f / 18f), size * (10f / 18f)))
        }
    }
}

private val shellKeys = setOf("terminal", "fish", "zsh", "bash", "nushell", "sh", "shell-container")

// Keys outside the catalogue (a hand-built ProgramKey) still get a tile.
private fun iconOf(program: ProgramKey): ProgramIcon = programIcons[program.value] ?: ProgramIcon.Generic
