package dev.mahali.hatch.renderer

import android.opengl.EGL14
import android.opengl.EGLExt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The device's GLES driver is stricter than desktop GLSL. */
@RunWith(AndroidJUnit4::class)
class ShaderCompileTest {
    @Test fun everyShippedShaderCompiles() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        EGL14.eglChooseConfig(
            display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0,
            configs, 0, 1, IntArray(1), 0,
        )
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)
        try {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val sources = app.assets.list("shaders")!!.associate { it to BuiltInShaders.asset(app, it.removeSuffix(".glsl")) } +
                mapOf("CRT" to BuiltInShaders.CRT, "Bloom" to BuiltInShaders.Bloom, "CursorGlow" to BuiltInShaders.CursorGlow)
            val failures = sources.mapNotNull { (name, source) -> runCatching { ShaderPass(source).release() }.exceptionOrNull()?.let { "$name: ${it.message}" } }
            assertEquals(12, sources.size)
            assertEquals(emptyList<String>(), failures)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}
