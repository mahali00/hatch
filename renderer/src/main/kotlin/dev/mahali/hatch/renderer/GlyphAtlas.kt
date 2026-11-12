package dev.mahali.hatch.renderer

import android.graphics.Rect
import androidx.collection.MutableLongObjectMap
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.opengl.GLES30
import kotlin.math.abs
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave

internal class Glyph(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val colored: Boolean, val cells: Int)

/**
 * Android's text engine rasterizes glyphs, so system fallback fonts cover emoji, CJK and symbols.
 * Monochrome glyphs are drawn white and tinted in the shader. Render thread only: it owns the GL texture.
 */
internal class GlyphAtlas(private val font: TerminalFont, private val cell: CellMetrics) {
    private val size = 2048
    private var texture = 0
    private var x = 0
    private var y = 0

    // Keyed without boxing: codepoint shl 4, then style bits and the wide flag.
    private val byCodepoint = MutableLongObjectMap<Glyph>(512)
    private val byText = HashMap<String, Glyph>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.WHITE; textSize = font.sizePx }
    private val scratch = createBitmap(cell.width * 2 + PAD * 2, cell.height + PAD * 2)
    private val canvas = Canvas(scratch)
    private val pixels = IntArray(scratch.width * scratch.height)
    private val bounds = Rect()

    /** Render thread, once its GL context exists. */
    fun create() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, size, size)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun bind() = GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)

    fun release() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        scratch.recycle()
    }

    fun glyph(codepoint: Int, style: Int, wide: Boolean): Glyph? {
        val key = (codepoint.toLong() shl 4) or style.toLong() or (if (wide) 8L else 0L)
        return byCodepoint[key] ?: rasterize(String(Character.toChars(codepoint)), style, wide)?.also { byCodepoint[key] = it }
    }

    fun glyph(text: String, style: Int, wide: Boolean): Glyph? {
        val key = "$style:$wide:$text"
        return byText[key] ?: rasterize(text, style, wide)?.also { byText[key] = it }
    }

    private fun rasterize(text: String, style: Int, wide: Boolean): Glyph? {
        val cells = if (wide) 2 else 1
        val w = cell.width * cells + PAD * 2
        val h = cell.height + PAD * 2
        if (x + w > size) { x = 0; y += h }
        if (y + h > size) { clear(); }
        paint.typeface = font.face(style and STYLE_BOLD != 0, style and STYLE_ITALIC != 0)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.withClip(0, 0, w, h) { drawFitted(text, cell.width * cells) }
        scratch.getPixels(pixels, 0, scratch.width, 0, 0, w, h)
        val colored = isColored(w, h)
        bind()
        val region = Bitmap.createBitmap(scratch, 0, 0, w, h)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, x, y, region)
        region.recycle()
        val glyph = Glyph(
            (x + PAD).toFloat() / size, (y + PAD).toFloat() / size,
            (x + PAD + cell.width * cells).toFloat() / size, (y + PAD + cell.height).toFloat() / size,
            colored, cells,
        )
        x += w
        return glyph
    }

    /** Fallback glyphs (a "⏺", a "⎿") can exceed the cell; scale them to fit, as Ghostty does for symbols. */
    private fun Canvas.drawFitted(text: String, boxWidth: Int) {
        paint.getTextBounds(text, 0, text.length, bounds)
        val top = cell.baseline + bounds.top
        val bottom = cell.baseline + bounds.bottom
        // Own glyphs may overhang a little (italics), hence the slack.
        val ownGlyph = abs(paint.measureText(text) - boxWidth) < 1f
        val slack = if (ownGlyph) cell.width * 0.2f else 1f
        val fits = bounds.left >= -slack && bounds.right <= boxWidth + slack && top >= -slack && bottom <= cell.height + slack
        if (fits || bounds.isEmpty) {
            drawText(text, PAD.toFloat(), (PAD + cell.baseline).toFloat(), paint)
            return
        }
        val scale = minOf(1f, boxWidth.toFloat() / bounds.width(), cell.height.toFloat() / bounds.height())
        val dx = (boxWidth - bounds.width() * scale) / 2f - bounds.left * scale
        val dy = (cell.height - bounds.height() * scale) / 2f - bounds.top * scale
        withSave {
            translate(PAD + dx, PAD + dy)
            scale(scale, scale)
            drawText(text, 0f, 0f, paint)
        }
    }

    private fun isColored(w: Int, h: Int): Boolean {
        for (row in 0 until h) for (col in 0 until w) {
            val p = pixels[row * scratch.width + col]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (r != g || g != b) return true
        }
        return false
    }

    private fun clear() {
        byCodepoint.clear()
        byText.clear()
        x = 0
        y = 0
    }

    companion object {
        const val PAD = 2
        const val STYLE_BOLD = 1
        const val STYLE_ITALIC = 2
    }
}
