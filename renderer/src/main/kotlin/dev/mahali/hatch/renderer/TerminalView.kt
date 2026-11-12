package dev.mahali.hatch.renderer

import androidx.core.graphics.withClip
import android.content.Context
import android.graphics.PixelFormat
import android.text.InputType
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.mahali.hatch.ghostty.FrameExchange

/** Keyboard events for a terminal. The view knows nothing about Rex. */
public interface TerminalInput {
    public fun onText(text: String)

    /** False passes the key to Android (volume, media; a numpad key with Num Lock off comes back as its arrow or Home/End). */
    public fun onKey(event: KeyEvent): Boolean

    public fun onBackspace(count: Int)

    public fun onContent(content: InputContentInfoCompat): Boolean = false

    public fun onPaste() {}
}

public data class HardwareRepeat(val enabled: Boolean = true, val delayMillis: Int = 400, val intervalMillis: Int = 100, val letters: Boolean = false)

public data class Grid(val cols: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int)

/**
 * Draws one terminal with OpenGL ES 3 on the shared render thread. A SurfaceView, not a TextureView:
 * a TextureView redrew the whole window for every terminal frame. Translucent until the first frame,
 * so no black flash. Touch belongs to the screen's gesture layer, so swipes stay with the pager.
 */
public class TerminalView(context: Context) : SurfaceView(context), SurfaceHolder.Callback2 {
    private val state = DrawState()
    private var thread: RenderTarget? = null
    private var font: TerminalFont? = null
    private var metrics: CellMetrics? = null
    private var presentedWidth = 0
    private var presentedHeight = 0
    internal val presentedSize: android.util.Size get() = android.util.Size(presentedWidth, presentedHeight)
    private var surfaceRevision = 0
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    public var input: TerminalInput? = null
        set(value) {
            if (field === value) return
            cancelRepeats()
            field = value
            if (isAttachedToWindow && hasFocus()) context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
    public var hardwareRepeat: HardwareRepeat = HardwareRepeat()
        set(value) { if (field != value) { cancelRepeats(); field = value } }
    private var hardwareMetaState = 0
    private val repeats = mutableMapOf<Int, Runnable>()

    private fun cancelRepeats() { repeats.values.forEach(ui::removeCallbacks); repeats.clear() }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelRepeats()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) cancelRepeats()
    }

    override fun onDetachedFromWindow() { cancelRepeats(); super.onDetachedFromWindow() }

    public var autocorrect: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (isAttachedToWindow && hasFocus()) context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }

    public var onGrid: (Grid) -> Unit = {}

    /**
     * UI thread: once a frame with content is on screen, its default background (what the program set
     * with OSC 11, else the theme's), and again whenever that changes. Setting it reports the current one.
     */
    public var onShown: ((Int) -> Unit)? = null
        set(value) {
            field = value
            shownBackground = null
            reportShown()
        }
    private var shownBackground: Int? = null

    private fun reportShown() {
        if (presentedWidth == 0) return
        val background = state.renderedBackground ?: return
        if (background == shownBackground) return
        shownBackground = background
        onShown?.invoke(background)
    }

    init {
        setWillNotDraw(false)
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = true
        isFocusableInTouchMode = true
        // Leaving touch mode on hardware typing greys a focused view; the pane's own focus treatment is enough.
        defaultFocusHighlightEnabled = false
        state.onBackground = { ui.post(::reportShown) }
    }

    // Clear only the presented buffer: a larger hole would show black during a resize.
    override fun draw(canvas: android.graphics.Canvas) {
        canvas.withClip(0, 0, width, height) {
            drawColor((state.renderedBackground ?: state.background) or 0xFF000000.toInt())
            withClip(0, 0, presentedWidth, presentedHeight) { super.draw(this) }
        }
    }

    public fun setSource(source: FrameExchange?) {
        if (state.source === source) return
        surfaceRevision++
        presentedWidth = 0; presentedHeight = 0
        // Stop the old consumer before releasing its SPSC exchange to a replacement view.
        thread?.finish()
        thread = null
        state.renderedBackground = null
        shownBackground = null
        state.source = source
        invalidate()
        if (source != null) restart()
    }

    /** Any thread. */
    public fun invalidateFrame() { thread?.requestRender() }

    public fun setAppearance(appearance: Appearance) {
        if (state.appearance == appearance) return
        state.appearance = appearance
        thread?.requestRender()
    }

    public var fullRate: Boolean
        get() = state.fullRate
        set(value) { state.fullRate = value }

    public fun setBlink(periodMillis: Int) {
        if (state.blinkMillis == periodMillis) return
        state.blinkMillis = periodMillis
        thread?.requestRender()
    }

    public fun setBackground(color: Int) {
        if (state.background == color) return
        state.background = color
        invalidate()
        thread?.requestRender()
    }

    public fun setFont(next: TerminalFont) {
        if (font == next) return
        font = next
        metrics = CellMetrics.of(next)
        restart()
    }

    public val cell: CellMetrics? get() = metrics

    public fun columnAt(x: Float): Int {
        val m = metrics ?: return 0
        val cols = ((width - state.padX * 2) / m.width).coerceAtLeast(1)
        return ((x - state.padX) / m.width).toInt().coerceIn(0, cols - 1)
    }

    /** Unclamped, so a drag that leaves the grid is visible to callers. */
    public fun rowAt(y: Float): Int {
        val m = metrics ?: return 0
        return Math.floorDiv(y.toInt() - state.padY, m.height)
    }

    public fun cellLeft(column: Int): Float = state.padX + column * (metrics?.width ?: 0).toFloat()
    public fun cellTop(row: Int): Float = state.padY + row * (metrics?.height ?: 0).toFloat()

    public var shader: String?
        get() = state.shader
        set(value) {
            if (state.shader == value) return
            state.shader = value
            thread?.requestRender()
        }

    public var anchorBottom: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            reportGrid(width, height)
            thread?.requestRender()
        }

    private fun restart() {
        surfaceRevision++
        thread?.finish()
        thread = null
        val surface = holder.surface?.takeIf { it.isValid } ?: return
        start(surface, width, height)
    }

    private fun start(surface: Surface, w: Int, h: Int) {
        if (state.source == null) return
        val f = font ?: return
        val m = metrics ?: return
        state.padX = sidePadding(context)
        val revision = surfaceRevision
        val running = TerminalGl.attach(surface, w, h, f, m, state)
        thread = running
        // Android may have finished its initial redraw with no source: a later consumer opens the hole after its first render.
        running.renderThen(Runnable {
            ui.post { if (thread === running) reveal(revision, w, h) }
        })
        reportGrid(w, h)
    }

    private fun reportGrid(w: Int, h: Int) {
        val m = metrics ?: return
        val cols = ((w - state.padX * 2) / m.width).coerceAtLeast(1)
        val rows = (h / m.height).coerceAtLeast(1)
        state.padY = if (anchorBottom) (h - rows * m.height).coerceAtLeast(0) else 0
        onGrid(Grid(cols, rows, m.width, m.height))
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceRevision++
        presentedWidth = 0; presentedHeight = 0
        if (thread == null && width > 0 && height > 0) start(holder.surface, width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        surfaceRevision++
        val running = thread
        if (running == null) start(holder.surface, w, h) else running.resize(w, h)
        reportGrid(w, h)
    }

    override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
        val revision = surfaceRevision
        val w = holder.surfaceFrame.width()
        val h = holder.surfaceFrame.height()
        // Called from the render thread: a UI-queue hop per pane stretched the frame hold (three panes froze the screen 50-110 ms).
        val ready = Runnable {
            drawingFinished.run()
            ui.post { reveal(revision, w, h) }
        }
        thread?.renderThen(ready) ?: ready.run()
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val revision = surfaceRevision
        val w = holder.surfaceFrame.width()
        val h = holder.surfaceFrame.height()
        val ready = Runnable {
            latch.countDown()
            ui.post { reveal(revision, w, h) }
        }
        thread?.renderThen(ready) ?: ready.run()
        if (latch.await(REDRAW_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) reveal(revision, w, h)
    }

    private fun reveal(revision: Int, w: Int, h: Int) {
        if (revision != surfaceRevision || thread == null) return
        presentedWidth = w; presentedHeight = h
        invalidate()
        reportShown()
    }

    /** Stop drawing before returning; the surface is freed once this returns. */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceRevision++
        thread?.finish()
        thread = null
    }

    public fun showKeyboard() {
        requestFocus()
        if (!hardwareKeyboard()) context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    /** With a hardware keyboard no IME is attached, so keys reach onKeyDown directly (no Ctrl+C-as-Copy, no composition). */
    private fun hardwareKeyboard(): Boolean = resources.configuration.let {
        it.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
            it.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (hardwareKeyboard()) imm.hideSoftInputFromWindow(windowToken, 0)
        imm.restartInput(this)
    }

    override fun onCheckIsTextEditor(): Boolean = !hardwareKeyboard()

    // createWrapper drops the keyboard's read grant when the listener returns; an upload needs it until its bytes are sent.
    @Suppress("DEPRECATION")
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (hardwareKeyboard()) return null
        outAttrs.inputType = if (autocorrect) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        EditorInfoCompat.setContentMimeTypes(outAttrs, arrayOf("image/*", "video/*"))
        val receiver = input
        val connection = TerminalConnection(receiver)
        return InputConnectionCompat.createWrapper(connection, outAttrs) { content, flags, _ ->
            if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) runCatching { content.requestPermission() }
            receiver?.onContent(content) ?: false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (escapeAsBack(event)) return onKeyDown(KeyEvent.KEYCODE_ESCAPE, event.withKey(KeyEvent.KEYCODE_ESCAPE))
        if (event.isSystem) return super.onKeyDown(keyCode, event)
        val receiver = input ?: return super.onKeyDown(keyCode, event)
        val hardware = event.deviceId >= 0 && event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0
        if (hardware) hardwareMetaState = event.metaState
        if (hardware && event.repeatCount > 0) return repeats.containsKey(keyCode) || super.onKeyDown(keyCode, event)
        if (!receiver.onKey(event)) return super.onKeyDown(keyCode, event)
        if (hardware && hardwareRepeat.enabled && !KeyEvent.isModifierKey(keyCode) &&
            (hardwareRepeat.letters || !Character.isLetter(event.unicodeChar))) {
            repeats.remove(keyCode)?.let(ui::removeCallbacks)
            var count = 0
            val repeat = object : Runnable {
                override fun run() {
                    if (!hasFocus() || !hasWindowFocus()) { repeats.remove(keyCode); return }
                    receiver.onKey(KeyEvent(event.downTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, event.keyCode, ++count, hardwareMetaState, event.deviceId, event.scanCode, event.flags, event.source))
                    ui.postDelayed(this, hardwareRepeat.intervalMillis.toLong())
                }
            }
            repeats[keyCode] = repeat
            ui.postDelayed(repeat, hardwareRepeat.delayMillis.toLong())
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.deviceId >= 0 && event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0) hardwareMetaState = event.metaState
        if (escapeAsBack(event)) { repeats.remove(KeyEvent.KEYCODE_ESCAPE)?.let(ui::removeCallbacks); return true }
        val held = repeats.remove(keyCode)?.also(ui::removeCallbacks) != null
        if (event.isSystem) return super.onKeyUp(keyCode, event)
        return held || input != null || super.onKeyUp(keyCode, event)
    }

    /** Some keyboards send Esc as Back. From an external keyboard it stays Escape; the phone's own Back gesture still goes back. */
    private fun escapeAsBack(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || input == null || android.os.Build.VERSION.SDK_INT < 29) return false
        val device = event.device ?: return false
        return device.isExternal && event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD) &&
            device.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    private fun KeyEvent.withKey(code: Int) = KeyEvent(downTime, eventTime, action, code, repeatCount, metaState, deviceId, scanCode, flags, source)

    /** Some IMEs send text as ACTION_MULTIPLE (ConnectBot does). */
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val text = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        input?.onText(text) ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        return true
    }

    private inner class TerminalConnection(private val receiver: TerminalInput?) : BaseInputConnection(this@TerminalView, false) {
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            // NFC: a decomposed ü (u + ¨) reaches the shell as one character.
            if (text.isNotEmpty()) receiver?.onText(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC))
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) receiver?.onText(composing)
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isNotEmpty()) { composing = composing.dropLast(beforeLength); return true }
            if (beforeLength > 0) receiver?.onBackspace(beforeLength)
            return true
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (id != android.R.id.paste) return super.performContextMenuAction(id)
            receiver?.onPaste()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            when {
                event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null -> receiver?.onText(event.characters)
                event.action != KeyEvent.ACTION_DOWN -> Unit
                event.keyCode == KeyEvent.KEYCODE_DEL && !event.isCtrlPressed && !event.isAltPressed -> receiver?.onBackspace(1)
                else -> receiver?.onKey(event)
            }
            return true
        }
    }


    public companion object {
        private const val REDRAW_WAIT_MILLIS = 100L
        private const val SIDE_PADDING_DP = 10

        /** The Mac's 10 pt side inset, so text does not touch the panel edge. */
        public fun sidePadding(context: Context): Int = (SIDE_PADDING_DP * context.resources.displayMetrics.density).toInt()
    }
}
