package dev.mahali.hatch.renderer

import android.opengl.GLES30

/** Ghostty-style shader pass, using Ghostty's uniform names. Render thread only. */
internal class ShaderPass(source: String) {
    private val program = TerminalRenderer.program(VERTEX, wrap(source))
    private val animated = "iTime" in source
    private val cursorDriven = "iTimeCursorChange" in source
    private var fbo = 0
    private var texture = 0
    private var width = 0
    private var height = 0
    private val started = System.nanoTime()
    private val current = FloatArray(4)
    private val previous = FloatArray(4)
    private var cursorChange = 0f
    private var cursorColor = 0

    fun begin(w: Int, h: Int) {
        if (w != width || h != height) allocate(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
    }

    fun end(w: Int, h: Int, cursor: FloatArray?, color: Int) {
        val now = seconds()
        if (cursor != null) {
            val glTop = h - cursor[1]
            if (cursor[0] != current[0] || glTop != current[1]) {
                current.copyInto(previous)
                cursorChange = now
            }
            current[0] = cursor[0]; current[1] = glTop; current[2] = cursor[2]; current[3] = cursor[3]
            if (cursorChange == 0f) current.copyInto(previous)
            cursorColor = color
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        fun at(name: String) = GLES30.glGetUniformLocation(program, name)
        GLES30.glUniform3f(at("iResolution"), w.toFloat(), h.toFloat(), 1f)
        GLES30.glUniform1f(at("iTime"), now)
        GLES30.glUniform4fv(at("iCurrentCursor"), 1, current, 0)
        GLES30.glUniform4fv(at("iPreviousCursor"), 1, previous, 0)
        GLES30.glUniform1f(at("iTimeCursorChange"), cursorChange)
        GLES30.glUniform4f(at("iCurrentCursorColor"),
            ((cursorColor shr 16) and 0xFF) / 255f, ((cursorColor shr 8) and 0xFF) / 255f, (cursorColor and 0xFF) / 255f, 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(at("iChannel0"), 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    fun wantsFrame(): Boolean = animated && (!cursorDriven || seconds() - cursorChange < CURSOR_SETTLE_SECONDS)

    fun release() {
        GLES30.glDeleteProgram(program)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    private fun allocate(w: Int, h: Int) {
        if (fbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
            GLES30.glGenTextures(1, ids, 0); texture = ids[0]
        }
        width = w; height = h
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun seconds() = (System.nanoTime() - started) / 1e9f

    private companion object {
        const val CURSOR_SETTLE_SECONDS = 0.6f

        const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

        fun wrap(source: String) = """#version 300 es
precision highp float;
uniform vec3 iResolution;
uniform float iTime;
uniform sampler2D iChannel0;
uniform vec4 iCurrentCursor;
uniform vec4 iPreviousCursor;
uniform float iTimeCursorChange;
uniform vec4 iCurrentCursorColor;
out vec4 hatchFragColor;
${portGlobals(source)}
void main() { mainImage(hatchFragColor, gl_FragCoord.xy); }
"""
    }
}

/**
 * GLSL ES needs constant global initializers; Ghostty shaders start globals from uniforms, so those become macros.
 * ponytail: matches unindented `type NAME = value;` lines only, which is how these shaders write globals.
 */
internal fun portGlobals(source: String): String = GLOBAL.replace(source) { "#define ${it.groupValues[1]} (${it.groupValues[2]})" }

private val GLOBAL = Regex("""(?m)^(?:vec[234]|float|int)\s+([A-Za-z_]\w*)\s*=\s*([^;\n]*);[^\n]*$""")

public object BuiltInShaders {
    private val assets = java.util.concurrent.ConcurrentHashMap<String, String>()

    public fun asset(context: android.content.Context, name: String): String =
        assets.getOrPut(name) { context.assets.open("shaders/$name.glsl").bufferedReader().use { it.readText() } }

    public const val CRT: String = """
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec2 c = uv - 0.5;
    float d = dot(c, c);
    vec2 bent = uv + c * d * 0.06;
    vec3 col = texture(iChannel0, bent).rgb;
    if (bent.x < 0.0 || bent.x > 1.0 || bent.y < 0.0 || bent.y > 1.0) col = vec3(0.0);
    col *= (0.92 + 0.08 * sin(fragCoord.y * 3.14159)) * (1.0 - d * 0.6);
    fragColor = vec4(col, 1.0);
}"""

    public const val Bloom: String = """
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec2 px = 2.0 / iResolution.xy;
    vec3 col = texture(iChannel0, uv).rgb;
    vec3 glow = vec3(0.0);
    for (int x = -2; x <= 2; x++)
        for (int y = -2; y <= 2; y++)
            glow += max(texture(iChannel0, uv + vec2(x, y) * px).rgb - 0.55, 0.0);
    fragColor = vec4(col + glow / 25.0 * 1.6, 1.0);
}"""

    public const val CursorGlow: String = """
float box(vec2 p, vec2 b) { vec2 d = abs(p) - b; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0); }
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec4 col = texture(iChannel0, fragCoord / iResolution.xy);
    vec2 cur = iCurrentCursor.xy + vec2(iCurrentCursor.z, -iCurrentCursor.w) * 0.5;
    vec2 prev = iPreviousCursor.xy + vec2(iPreviousCursor.z, -iPreviousCursor.w) * 0.5;
    float age = clamp((iTime - iTimeCursorChange) / 0.25, 0.0, 1.0);
    vec2 pa = fragCoord - prev, ba = cur - prev;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
    float trail = (1.0 - age) * smoothstep(6.0, 0.0, length(pa - ba * h) - iCurrentCursor.w * 0.35) * h;
    float glow = exp(-max(box(fragCoord - cur, iCurrentCursor.zw * 0.5), 0.0) / 8.0) * 0.35;
    col.rgb += iCurrentCursorColor.rgb * (glow + trail * 0.6);
    fragColor = col;
}"""
}
