package dev.mahali.hatch.renderer

import android.opengl.GLES30
import android.os.Trace
import androidx.collection.MutableLongIntMap
import dev.mahali.hatch.ghostty.FrameBuffer
import dev.mahali.hatch.ghostty.ScreenCell
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

public data class Appearance(
    val selection: Pair<ScreenCell, ScreenCell>? = null,
    val selectionColor: Int = 0x553D7BFF,
    val matchColor: Int = 0x66D9A400,
    val currentMatchColor: Int = 0xCCF5B800u.toInt(),
    val focused: Boolean = true,
    val faded: Boolean = false,
    val lightTheme: Boolean = false,
    val improveContrast: Boolean = true,
    val textPalette: List<Int> = emptyList(),
    val cursorStyle: Int? = null,
)

/** Draws one frame in two instanced passes: rectangles, then glyphs. Render thread only. */
internal class TerminalRenderer(font: TerminalFont, val cell: CellMetrics) {
    private val textPalette = TextPalette()
    private val atlas = GlyphAtlas(font, cell)
    private var rectProgram = 0
    private var glyphProgram = 0
    private var quad = 0
    private var rectBuffer = 0
    private var glyphBuffer = 0
    private var rectVao = 0
    private var glyphVao = 0
    private var rects: FloatBuffer = floats(RECT_FLOATS * 4096)
    private var glyphs: FloatBuffer = floats(GLYPH_FLOATS * 4096)
    // Staged in arrays: per-float puts on a direct buffer are slow.
    private var rectData = FloatArray(RECT_FLOATS * 4096)
    private var glyphData = FloatArray(GLYPH_FLOATS * 4096)
    private var rectViewport = 0
    private var glyphViewport = 0
    private var rectCount = 0
    private var glyphCount = 0
    private val text = StringBuilder()

    fun create() {
        rectProgram = program(RECT_VERTEX, RECT_FRAGMENT)
        glyphProgram = program(GLYPH_VERTEX, GLYPH_FRAGMENT)
        rectViewport = GLES30.glGetUniformLocation(rectProgram, "uViewport")
        glyphViewport = GLES30.glGetUniformLocation(glyphProgram, "uViewport")
        val ids = IntArray(3)
        GLES30.glGenBuffers(3, ids, 0)
        quad = ids[0]; rectBuffer = ids[1]; glyphBuffer = ids[2]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        val corners = floats(8).apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); flip() }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, corners, GLES30.GL_STATIC_DRAW)
        val vaos = IntArray(2)
        GLES30.glGenVertexArrays(2, vaos, 0)
        rectVao = vaos[0]; glyphVao = vaos[1]
        setUpVao(rectVao, rectBuffer, intArrayOf(4, 4))
        setUpVao(glyphVao, glyphBuffer, intArrayOf(4, 4, 4, 1))
        atlas.create()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun release() {
        atlas.release()
        GLES30.glDeleteProgram(rectProgram)
        GLES30.glDeleteProgram(glyphProgram)
        GLES30.glDeleteBuffers(3, intArrayOf(quad, rectBuffer, glyphBuffer), 0)
        GLES30.glDeleteVertexArrays(2, intArrayOf(rectVao, glyphVao), 0)
    }

    fun draw(frame: FrameBuffer?, width: Int, height: Int, padX: Int, padY: Int, background: Int, appearance: Appearance, cursorOn: Boolean) {
        GLES30.glViewport(0, 0, width, height)
        // Matches Rex's "Fade inactive panes: both", measured on the Mac: surface 80%, content 70% over it.
        surfaceFade = if (!appearance.faded) 1f else if (appearance.lightTheme) LIGHT_SURFACE_FADE else SURFACE_FADE
        contentFade = if (appearance.faded) CONTENT_FADE else 1f
        val bg = frame?.background ?: background
        GLES30.glClearColor(red(bg) * surfaceFade, green(bg) * surfaceFade, blue(bg) * surfaceFade, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        cursorRect = null
        if (frame == null || frame.cols == 0) return
        Trace.beginSection("Hatch.build")
        try { build(frame, padX, padY, appearance, cursorOn) } finally { Trace.endSection() }
        drawPass(rectProgram, rectViewport, rectVao, rectBuffer, rects, rectData, rectCount, RECT_FLOATS, width, height)
        atlas.bind()
        drawPass(glyphProgram, glyphViewport, glyphVao, glyphBuffer, glyphs, glyphData, glyphCount, GLYPH_FLOATS, width, height)
    }

    private fun build(frame: FrameBuffer, padX: Int, gridTop: Int, appearance: Appearance, cursorOn: Boolean) {
        val cols = frame.cols
        val rows = frame.rows
        val padY = gridTop + frame.shift
        val firstRow = if (frame.shift > 0 && frame.rowsAbove > 0) -1 else 0
        ensure(cols * (rows + 1) * 5 + 8, cols * (rows + 1) + 8)
        rectCount = 0; glyphCount = 0
        val defaultBg = frame.background
        val cw = cell.width.toFloat()
        val ch = cell.height.toFloat()
        val cursorIndex = if (frame.cursorVisible && frame.cursorY in 0 until rows && frame.cursorX in 0 until cols) frame.cursorY * cols + frame.cursorX else -1
        val solidCursor = cursorIndex >= 0 && cursorOn && appearance.focused && (appearance.cursorStyle ?: frame.cursorStyle) == 1
        val sel = appearance.selection
        val selStart = if (sel != null && sel.first <= sel.second) sel.first else sel?.second
        val selEnd = if (sel != null && sel.first <= sel.second) sel.second else sel?.first
        val selecting = selStart != null && selEnd != null
        val sy0 = selStart?.y ?: 0; val sx0 = selStart?.x ?: 0; val sy1 = selEnd?.y ?: 0; val sx1 = selEnd?.x ?: 0
        var mapped = false; var mapIn = 0; var mapOut = 0
        var readable = false; var readFg = 0; var readBg = 0; var readOut = 0

        for (y in firstRow until rows) {
            val top = padY + y * ch
            // The row above is stored after the viewport's rows.
            val rowStart = if (y < 0) rows * cols else y * cols
            var x = 0
            while (x < cols) {
                val i = rowStart + x
                val flags = frame.flags(i)
                val left = padX + x * cw
                val span = if (flags and FrameBuffer.WIDE != 0) 2 else 1
                var fg = frame.foreground(i)
                var cellBg = frame.background(i)
                if (i == cursorIndex && solidCursor) {
                    val cursorColor = frame.cursorColor.takeIf { it >= 0 } ?: fg
                    fg = cellBg; cellBg = cursorColor
                }
                if (flags and FrameBuffer.DEFAULT_BACKGROUND == 0 || cellBg != defaultBg) surfaceRect(left, top, cw * span, ch, cellBg)
                if (flags and (FrameBuffer.MATCH or FrameBuffer.MATCH_CURRENT) != 0) {
                    val c = if (flags and FrameBuffer.MATCH_CURRENT != 0) appearance.currentMatchColor else appearance.matchColor
                    rect(left, top, cw * span, ch, c and 0xFFFFFF, ((c ushr 24) and 0xFF) / 255f)
                }
                if (selecting) {
                    val row = frame.scrollOffset + y
                    if ((row > sy0 || row == sy0 && x >= sx0) && (row < sy1 || row == sy1 && x <= sx1)) rect(left, top, cw * span, ch, appearance.selectionColor and 0xFFFFFF, ((appearance.selectionColor ushr 24) and 0xFF) / 255f)
                }
                if (flags and FrameBuffer.SPACER == 0 && flags and FrameBuffer.INVISIBLE == 0) {
                    val alpha = (if (flags and FrameBuffer.FAINT != 0) 0.55f else 1f) * contentFade
                    // Contrast is for text: OpenCode draws its input edge with faint block characters; lifting those paints bars.
                    val cp = frame.codepoint(i)
                    val graphic = isGraphic(cp)
                    if (!graphic) {
                        if (!mapped || fg != mapIn) { mapped = true; mapIn = fg; mapOut = textPalette.map(fg, cp, appearance.textPalette) }
                        fg = mapOut
                    }
                    val color = if (appearance.improveContrast && !graphic) {
                        if (!readable || fg != readFg || cellBg != readBg) { readable = true; readFg = fg; readBg = cellBg; readOut = readableCached(fg, cellBg) }
                        readOut
                    } else fg
                    glyph(frame, i, cp, flags, left, top, color, alpha, span == 2)
                    if (flags and FrameBuffer.UNDERLINE != 0) rect(left, top + cell.baseline + 2f, cw * span, maxOf(1f, ch / 16f), color, alpha)
                    if (flags and FrameBuffer.STRIKE != 0) rect(left, top + ch * 0.55f, cw * span, maxOf(1f, ch / 16f), color, alpha)
                }
                x += span
            }
        }
        if (cursorIndex >= 0 && cursorOn && !solidCursor) cursor(frame, cursorIndex, padX, padY, appearance.focused, appearance.cursorStyle ?: frame.cursorStyle)
    }

    private val contrast = MutableLongIntMap()

    private fun readableCached(fg: Int, bg: Int): Int {
        val key = (fg.toLong() shl 32) or (bg.toLong() and 0xFFFFFFFFL)
        if (key in contrast) return contrast[key]
        if (contrast.size > 4096) contrast.clear()
        return Contrast.readable(fg, bg).also { contrast[key] = it }
    }

    private fun cursor(frame: FrameBuffer, index: Int, padX: Int, padY: Int, focused: Boolean, style: Int) {
        val cols = frame.cols
        val left = padX + (index % cols) * cell.width.toFloat()
        val top = padY + (index / cols) * cell.height.toFloat()
        val color = frame.cursorColor.takeIf { it >= 0 } ?: frame.foreground(index)
        val w = cell.width.toFloat(); val h = cell.height.toFloat()
        cursorRect = floatArrayOf(left, top, w, h)
        cursorColor = color
        val t = maxOf(1f, w / 8f)
        when {
            !focused || style == 3 -> {
                rect(left, top, w, t, color, contentFade); rect(left, top + h - t, w, t, color, contentFade)
                rect(left, top, t, h, color, contentFade); rect(left + w - t, top, t, h, color, contentFade)
            }
            style == 0 -> rect(left, top, t * 1.5f, h, color, contentFade)
            style == 2 -> rect(left, top + h - t * 1.5f, w, t * 1.5f, color, contentFade)
        }
    }

    private fun glyph(frame: FrameBuffer, index: Int, cp: Int, flags: Int, left: Float, top: Float, color: Int, alpha: Float, wide: Boolean) {
        if (cp == 0 || cp == ' '.code) return
        val style = (if (flags and FrameBuffer.BOLD != 0) GlyphAtlas.STYLE_BOLD else 0) or (if (flags and FrameBuffer.ITALIC != 0) GlyphAtlas.STYLE_ITALIC else 0)
        val g = if (cp > 0) atlas.glyph(cp, style, wide) else {
            text.setLength(0); frame.appendText(index, text); atlas.glyph(text.toString(), style, wide)
        } ?: return
        val d = glyphData
        var n = glyphCount * GLYPH_FLOATS
        d[n++] = left; d[n++] = top; d[n++] = cell.width * g.cells.toFloat(); d[n++] = cell.height.toFloat()
        d[n++] = g.u0; d[n++] = g.v0; d[n++] = g.u1; d[n++] = g.v1
        d[n++] = red(color) * alpha; d[n++] = green(color) * alpha; d[n++] = blue(color) * alpha; d[n++] = alpha
        d[n] = if (g.colored) 1f else 0f
        glyphCount++
    }

    private var surfaceFade = 1f
    private var contentFade = 1f

    private fun surfaceRect(left: Float, top: Float, w: Float, h: Float, color: Int) {
        putRect(left, top, w, h, red(color) * surfaceFade, green(color) * surfaceFade, blue(color) * surfaceFade, 1f)
    }

    private fun rect(left: Float, top: Float, w: Float, h: Float, color: Int, alpha: Float) {
        putRect(left, top, w, h, red(color) * alpha, green(color) * alpha, blue(color) * alpha, alpha)
    }

    private fun putRect(left: Float, top: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float) {
        val d = rectData
        var n = rectCount * RECT_FLOATS
        d[n++] = left; d[n++] = top; d[n++] = w; d[n++] = h
        d[n++] = r; d[n++] = g; d[n++] = b; d[n] = a
        rectCount++
    }

    private fun drawPass(program: Int, viewport: Int, vao: Int, buffer: Int, data: FloatBuffer, staged: FloatArray, count: Int, stride: Int, width: Int, height: Int) {
        if (count == 0) return
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(viewport, width.toFloat(), height.toFloat())
        data.clear(); data.put(staged, 0, count * stride); data.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.limit() * 4, data, GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        GLES30.glBindVertexArray(0)
    }

    private fun setUpVao(vao: Int, instances: Int, sizes: IntArray) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instances)
        val stride = sizes.sum() * 4
        var offset = 0
        sizes.forEachIndexed { i, n ->
            GLES30.glEnableVertexAttribArray(i + 1)
            GLES30.glVertexAttribPointer(i + 1, n, GLES30.GL_FLOAT, false, stride, offset)
            GLES30.glVertexAttribDivisor(i + 1, 1)
            offset += n * 4
        }
        GLES30.glBindVertexArray(0)
    }

    private fun ensure(rectInstances: Int, glyphInstances: Int) {
        if (rects.capacity() < rectInstances * RECT_FLOATS) { rects = floats(rectInstances * RECT_FLOATS); rectData = FloatArray(rectInstances * RECT_FLOATS) }
        if (glyphs.capacity() < glyphInstances * GLYPH_FLOATS) { glyphs = floats(glyphInstances * GLYPH_FLOATS); glyphData = FloatArray(glyphInstances * GLYPH_FLOATS) }
    }

    /** Read by the shader pass: (left, top, width, height) in view pixels. */
    var cursorRect: FloatArray? = null
        private set
    var cursorColor: Int = 0
        private set

    internal companion object {
        const val SURFACE_FADE = 0.8f

        /** Rex on a light theme darkens an unfocused surface by about 2 %, measured on the Mac. */
        const val LIGHT_SURFACE_FADE = 0.98f
        const val CONTENT_FADE = 0.7f
        const val RECT_FLOATS = 8
        const val GLYPH_FLOATS = 13

        fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun red(c: Int) = ((c shr 16) and 0xFF) / 255f
        fun green(c: Int) = ((c shr 8) and 0xFF) / 255f
        fun blue(c: Int) = (c and 0xFF) / 255f

        fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, src: String) = GLES30.glCreateShader(type).also {
                GLES30.glShaderSource(it, src)
                GLES30.glCompileShader(it)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(it) }
            }
            return GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, shader(GLES30.GL_VERTEX_SHADER, vertex))
                GLES30.glAttachShader(it, shader(GLES30.GL_FRAGMENT_SHADER, fragment))
                GLES30.glLinkProgram(it)
            }
        }

        const val RECT_VERTEX = """#version 300 es
layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec4 aRect;
layout(location = 2) in vec4 aColor;
uniform vec2 uViewport;
out vec4 vColor;
void main() {
    vec2 p = aRect.xy + aCorner * aRect.zw;
    gl_Position = vec4(p.x / uViewport.x * 2.0 - 1.0, 1.0 - p.y / uViewport.y * 2.0, 0.0, 1.0);
    vColor = aColor;
}"""
        const val RECT_FRAGMENT = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 outColor;
void main() { outColor = vColor; }"""
        const val GLYPH_VERTEX = """#version 300 es
layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec4 aRect;
layout(location = 2) in vec4 aUv;
layout(location = 3) in vec4 aColor;
layout(location = 4) in float aColored;
uniform vec2 uViewport;
out vec2 vUv;
out vec4 vColor;
out float vColored;
void main() {
    vec2 p = aRect.xy + aCorner * aRect.zw;
    gl_Position = vec4(p.x / uViewport.x * 2.0 - 1.0, 1.0 - p.y / uViewport.y * 2.0, 0.0, 1.0);
    vUv = mix(aUv.xy, aUv.zw, aCorner);
    vColor = aColor;
    vColored = aColored;
}"""
        const val GLYPH_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D uAtlas;
in vec2 vUv;
in vec4 vColor;
in float vColored;
out vec4 outColor;
void main() {
    vec4 t = texture(uAtlas, vUv);
    outColor = vColored > 0.5 ? t * vColor.a : vColor * t.a;
}"""
    }

}
