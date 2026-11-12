package dev.mahali.hatch.renderer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.Surface
import dev.mahali.hatch.ghostty.FrameExchange
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Set by the UI thread; [renderedBackground] is written by the render thread. */
internal class DrawState(
    @Volatile var source: FrameExchange? = null,
    @Volatile var appearance: Appearance = Appearance(),
    @Volatile var fullRate: Boolean = true,
    @Volatile var background: Int = 0,
    @Volatile var renderedBackground: Int? = null,
    @Volatile var padX: Int = 0,
    @Volatile var padY: Int = 0,
    @Volatile var shader: String? = null,
    @Volatile var blinkMillis: Int = 600,
    /** Render thread: the frame's default background changed. */
    @Volatile var onBackground: (() -> Unit)? = null,
)

/** Any thread may call its methods; drawing happens on its [GlThread]. */
internal class RenderTarget(
    val window: Surface,
    width: Int,
    height: Int,
    val font: TerminalFont,
    val cell: CellMetrics,
    val state: DrawState,
    private val gl: GlThread,
) {
    private var width = width
    private var height = height
    // GL thread only.
    var pass: ShaderPass? = null
    var passSource: String? = null
    private var requested = true
    private var running = true
    private val afterSwap = ArrayList<Runnable>()

    // Render thread only.
    var egl: EGLSurface = EGL14.EGL_NO_SURFACE
    var renderer: TerminalRenderer? = null
    var drawnWidth = 0
    var drawnHeight = 0
    var cursorOn = true
    var nextBlink = SystemClock.uptimeMillis() + state.blinkMillis
    var fast = false
    var lastContent = 0L
    var counted = true
    var rate = 0f

    fun requestRender() { synchronized(this) { requested = true }; gl.schedule() }

    fun resize(w: Int, h: Int) { synchronized(this) { width = w; height = h; requested = true }; gl.schedule() }

    /** Completes after a frame started after this request, or when rendering stops. */
    fun renderThen(done: Runnable) {
        val queued = synchronized(this) { if (running) { afterSwap += done; requested = true }; running }
        if (queued) gl.schedule() else done.run()
    }

    /** Stops drawing into [window]; returns once the render thread has let go of it. */
    fun finish() {
        synchronized(this) { running = false }
        gl.detach(this)
    }

    /** Render thread only. */
    fun take(): Pending? = synchronized(this) {
        if (!running) return null
        Pending(requested, width, height, afterSwap.toList()).also { requested = false; afterSwap.clear() }
    }

    fun leftovers(): List<Runnable> = synchronized(this) { afterSwap.toList().also { afterSwap.clear() } }

    class Pending(val content: Boolean, val width: Int, val height: Int, val redraws: List<Runnable>)
}

/**
 * A pane costs a surface, not a context or a 16 MB atlas. One thread misses a 120 Hz frame with
 * twelve streaming panes (about 1 ms of vertex building each); three do not.
 */
internal object TerminalGl {
    /** Set once at startup, before the first pane draws; see [TerminalRendering.lowEnd]. */
    @Volatile var lowEnd = false

    // Each thread costs an EGL context and an atlas per font: a low-end phone gets one.
    private val threads by lazy {
        List(if (lowEnd) 1 else (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)) { GlThread("terminal-render-$it") }
    }

    fun attach(window: Surface, width: Int, height: Int, font: TerminalFont, cell: CellMetrics, state: DrawState): RenderTarget =
        threads.minBy { it.load.get() }.attach(window, width, height, font, cell, state)
}

private const val SHADER_FRAME_MILLIS = 16L

internal class GlThread(name: String) {
    private val thread = HandlerThread(name, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val scheduled = AtomicBoolean(false)
    val load = AtomicInteger(0)

    // Render thread only.
    private lateinit var display: EGLDisplay
    private lateinit var config: EGLConfig
    private lateinit var context: EGLContext
    private lateinit var idle: EGLSurface
    private val targets = ArrayList<RenderTarget>()
    private val renderers = HashMap<Pair<TerminalFont, CellMetrics>, Pair<TerminalRenderer, Int>>()
    private var framePending = false
    private val frame = Runnable { framePending = false; drawFrame() }
    private val wake = Runnable { requestFrame() }

    init { handler.post(::setUp) }

    fun attach(window: Surface, width: Int, height: Int, font: TerminalFont, cell: CellMetrics, state: DrawState): RenderTarget =
        RenderTarget(window, width, height, font, cell, state, this).also { target -> load.incrementAndGet(); handler.post { add(target) } }

    /** Any thread: draw the terminals that asked, soon. */
    fun schedule() {
        if (scheduled.compareAndSet(false, true)) handler.post { scheduled.set(false); requestFrame() }
    }

    fun detach(target: RenderTarget) {
        if (Thread.currentThread() === thread) { remove(target); return }
        val done = CountDownLatch(1)
        handler.post { try { remove(target) } finally { done.countDown() } }
        // Must block: Android frees the window once surfaceDestroyed returns. A font restart reattaches
        // the same window, which fails while this thread still holds it. A timeout is no acknowledgment:
        // another GL thread could still consume the SPSC exchange. Ownership moves only after removal.
        var interrupted = false
        while (true) {
            try { done.await(); break } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        config = chooseConfig(display)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        // Current while no terminal is: GL objects are created and deleted against it.
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeIdle()
    }

    private fun makeIdle() { EGL14.eglMakeCurrent(display, idle, idle, context) }

    private fun add(target: RenderTarget) {
        target.egl = EGL14.eglCreateWindowSurface(display, config, target.window, intArrayOf(EGL14.EGL_NONE), 0)
        if (target.egl == EGL14.EGL_NO_SURFACE) { target.leftovers().forEach { it.run() }; return }
        // Never block in a swap: this thread draws several terminals. Panes publish at most 120 frames a second.
        EGL14.eglMakeCurrent(display, target.egl, target.egl, context)
        EGL14.eglSwapInterval(display, 0)
        makeIdle()
        val key = target.font to target.cell
        val (renderer, users) = renderers[key] ?: (TerminalRenderer(target.font, target.cell).apply { create() } to 0)
        renderers[key] = renderer to users + 1
        target.renderer = renderer
        targets += target
        requestFrame()
    }

    private fun remove(target: RenderTarget) {
        if (target.counted) { target.counted = false; load.decrementAndGet() }
        if (targets.remove(target)) {
            if (target.pass != null && EGL14.eglMakeCurrent(display, target.egl, target.egl, context)) target.pass?.release()
            target.pass = null
            makeIdle()
            EGL14.eglDestroySurface(display, target.egl)
            target.egl = EGL14.EGL_NO_SURFACE
            if (target.fast) requestRate(target, 0f)
            val key = target.font to target.cell
            renderers[key]?.let { (renderer, users) ->
                if (users <= 1) { renderer.release(); renderers.remove(key) } else renderers[key] = renderer to users - 1
            }
        }
        target.leftovers().forEach { it.run() }
    }

    // Draws as soon as asked rather than on the next vsync: waiting for Choreographer cost 4% more
    // CPU and a frame per second per pane with twelve streaming (measured against a thread per pane).
    private fun requestFrame() {
        if (framePending) return
        framePending = true
        handler.removeCallbacks(wake)
        handler.post(frame)
    }

    private fun drawFrame() {
        val now = SystemClock.uptimeMillis()
        var nextWake = Long.MAX_VALUE
        for (target in targets.toList()) {
            val pending = target.take() ?: continue
            val state = target.state
            val blink = state.blinkMillis > 0
            // Content changes ask the display for its top rate (a 120 Hz panel otherwise stays at 60); the
            // request is dropped 1 s after the last change, and blinks never ask. Only a focused pane asks:
            // an unfocused one is capped at 30 a second, and asking for 120 would keep the panel there.
            val rate = if (!state.fullRate) UNFOCUSED_RATE else if (TerminalGl.lowEnd) LOW_END_RATE else HIGH_RATE
            if (pending.content) {
                target.lastContent = now
                if (!target.fast || target.rate != rate) { target.fast = true; target.rate = rate; requestRate(target, rate) }
            } else if (target.fast && now - target.lastContent >= IDLE_MILLIS) {
                target.fast = false
                requestRate(target, 0f)
            }
            var toggled = false
            if (blink && now >= target.nextBlink) { target.cursorOn = !target.cursorOn; target.nextBlink = now + state.blinkMillis; toggled = true }
            if (!blink) target.cursorOn = true
            if (pending.content || toggled || pending.redraws.isNotEmpty()) draw(target, pending)
            if (blink) nextWake = minOf(nextWake, target.nextBlink)
            if (target.fast) nextWake = minOf(nextWake, target.lastContent + IDLE_MILLIS)
        }
        handler.removeCallbacks(wake)
        if (nextWake != Long.MAX_VALUE) handler.postAtTime(wake, nextWake)
    }

    private fun draw(target: RenderTarget, pending: RenderTarget.Pending) {
        val renderer = target.renderer ?: return
        val state = target.state
        try {
            if (!EGL14.eglMakeCurrent(display, target.egl, target.egl, context)) return
            val frame = state.source?.take()
            val background = frame?.background
            val changed = background != null && background != state.renderedBackground
            state.renderedBackground = background
            if (changed) state.onBackground?.invoke()
            val source = state.shader
            if (source != target.passSource) {
                target.pass?.release()
                target.pass = source?.let { runCatching { ShaderPass(it) }.onFailure { e -> android.util.Log.w("Hatch", "Shader left off: ${e.message}") }.getOrNull() }
                target.passSource = source
            }
            val pass = target.pass
            fun drawOnce() {
                Trace.beginSection("Hatch.draw")
                try {
                    pass?.begin(pending.width, pending.height)
                    renderer.draw(frame, pending.width, pending.height, state.padX, state.padY, state.background, state.appearance, target.cursorOn)
                    pass?.end(pending.width, pending.height, renderer.cursorRect, renderer.cursorColor)
                    EGL14.eglSwapBuffers(display, target.egl)
                } finally { Trace.endSection() }
            }
            // The first swap after a resize can still use the old-size EGL buffer.
            // Prime it before completing SurfaceView's redraw transaction.
            if (pending.width != target.drawnWidth || pending.height != target.drawnHeight) {
                drawOnce()
                target.drawnWidth = pending.width; target.drawnHeight = pending.height
            }
            drawOnce()
            if (pass?.wantsFrame() == true) handler.postDelayed({ target.requestRender() }, SHADER_FRAME_MILLIS)
        } finally {
            pending.redraws.forEach { it.run() }
        }
    }

    /** Frame-rate hint; 0 clears it. */
    private fun requestRate(target: RenderTarget, rate: Float) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching { target.window.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, IntArray(1), 0)
        return configs[0] ?: error("No GLES3 EGL config")
    }
}

/** Above the panel's top rate is harmless: the system picks the nearest it has (120 on the Fold). */
private const val HIGH_RATE = 120f

/** A low-end phone's focused pane: 120 Hz output costs it more than it shows. */
private const val LOW_END_RATE = 60f

private const val UNFOCUSED_RATE = 30f

private const val IDLE_MILLIS = 1_000L

public object TerminalRendering {
    /** Call before any pane draws. One GL thread and at most 60 Hz for the focused pane. */
    public fun lowEnd() { TerminalGl.lowEnd = true }
}
