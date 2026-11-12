package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.renderer.BuiltInShaders
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.PanePriority
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.TerminalTheme
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.engine.PaneAttachment
import dev.mahali.hatch.engine.TerminalColors
import dev.mahali.hatch.ghostty.CursorShape
import dev.mahali.hatch.protocol.DataStream
import dev.mahali.hatch.renderer.Appearance
import dev.mahali.hatch.renderer.TerminalInput
import dev.mahali.hatch.renderer.HardwareRepeat
import dev.mahali.hatch.renderer.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class PaneServices(
    val rex: Rex, val typing: Typing, val uploads: Uploads, val haptics: Haptics, val connection: dev.mahali.hatch.protocol.HostSync?,
    val onCommands: () -> Unit,
    val onLastPane: () -> Unit,
    // The focused pane's frames are on screen; with their program background.
    val onShown: (Int) -> Unit,
) {
    // Set when the user opens the keyboard in this tab; it stays up as focus moves between panes.
    var keyboardWanted: Boolean = false

    // The owning pane shows it in the same menu as a tap.
    var offeredLink by mutableStateOf<OfferedLink?>(null)

    // A text field in this screen (Find) has the keys; the focused pane takes them back when it lets go.
    var fieldFocused by mutableStateOf(false)

}

// Identity matters: the same URL again is a new offer.
internal class OfferedLink(val block: BlockId, val url: String)

// Frames go straight to the view; nothing here recomposes per frame.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalPane(
    services: PaneServices,
    viewport: TerminalViewport,
    ref: PaneRef,
    theme: TerminalTheme,
    settings: Settings,
    focused: Boolean,
    hostOnline: Boolean,
    exited: Boolean,
    onTap: () -> Unit,
    onClosePane: () -> Unit,
    modifier: Modifier = Modifier,
    faded: Boolean = false,
) {
    val rex = services.rex
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val acquisition = remember(ref, services.connection) { rex.acquire(ref.host, ref.session, ref.block) } ?: return
    val pane = acquisition.pane
    DisposableEffect(acquisition) { onDispose { acquisition.close() } }
    val target = remember(acquisition) { Target(ref, acquisition) }
    val status by pane.status.collectAsState()

    val font = remember(settings.fontFamily, settings.fontSize, settings.lineHeight) { Fonts.terminal(context, settings) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var visualAttached by remember(acquisition) { mutableStateOf(false) }
    var visualAttachment by remember(acquisition) { mutableStateOf<PaneAttachment?>(null) }
    val selection = remember(pane) { Selection() }
    val copied = remember { CopyFlash(scope) }
    var link by remember { mutableStateOf<PressedLink?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val offered = services.offeredLink
    LaunchedEffect(offered) {
        val offer = offered ?: return@LaunchedEffect
        if (offer.block != ref.block) return@LaunchedEffect
        link = PressedLink(offer.url, Offset.Zero, remote = true)
        if (services.offeredLink === offer) services.offeredLink = null
    }
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(pane, theme) { pane.setLocalColors(theme.toColors()) }
    val slot = LocalSlotClip.current
    LaunchedEffect(view, slot) {
        val v = view ?: return@LaunchedEffect
        if (slot == null) return@LaunchedEffect
        snapshotFlow { slot.size }.collect { s -> v.clipBounds = s?.let { android.graphics.Rect(0, 0, it.width, it.height) } }
    }
    LaunchedEffect(settings.cursorStyle, settings.blink) {
        pane.setCursorDefaults(settings.cursorStyle.shape(), settings.blink != Settings.Blink.Off)
    }
    // Slides the terminal just far enough to keep the cursor line in view, as on iOS. Sampled per frame; told to the
    // viewport only when it changes, and forgotten when the keyboard goes or the pane leaves.
    LaunchedEffect(view, imeVisible, viewport) {
        val v = view ?: return@LaunchedEffect
        if (!imeVisible) return@LaunchedEffect
        var told = Int.MIN_VALUE
        try {
            while (true) {
                withFrameNanos { }
                val g = pane.geometry
                val rows = g.displayed?.rows ?: 0
                val bottom = keyboardRows(g.modes.alternateScreen || g.modes.mouseTracking, g.cursor?.row, rows)
                    ?.let { if (it >= rows) v.height else v.cellTop(it).toInt() } ?: -1
                if (bottom != told) { viewport.cursor(ref.block, bottom.toFloat()); told = bottom }
            }
        } finally {
            viewport.cursor(ref.block, null)
        }
    }
    // The keyboard never opens on its own; the focused pane takes its input.
    val keyboardUp by rememberUpdatedState(imeVisible)
    val focusedNow by rememberUpdatedState(focused)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var sawKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, focused, active) {
        if (!active) sawKeyboard = false
        if (focused && active && view?.hasWindowFocus() == true) {
            if (imeVisible) { sawKeyboard = true; rex.rememberKeyboard(ref.host, ref.block, true) }
            else if (sawKeyboard) { rex.rememberKeyboard(ref.host, ref.block, false); sawKeyboard = false }
        }
    }
    var focusedBefore by remember { mutableStateOf(false) }
    LaunchedEffect(focused, view, active, settings.keepKeyboardOpen, services.fieldFocused) {
        val v = view ?: return@LaunchedEffect
        if (!focused || !active) { focusedBefore = false; return@LaunchedEffect }
        if (services.fieldFocused) return@LaunchedEffect
        v.requestFocus()
        if ((!focusedBefore && keyboardUp && services.keyboardWanted) || (settings.keepKeyboardOpen && rex.keyboardWasOpen(ref.host, ref.block))) {
            services.keyboardWanted = true
            v.showKeyboard()
        }
        focusedBefore = true
    }
    // A text field elsewhere in the window (the wide sidebar's search) clearing its focus leaves the
    // window's keys with nothing; the focused pane takes them back.
    DisposableEffect(view, focused, active) {
        val v = view
        val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
            if (now === v) return@OnGlobalFocusChangeListener
            // Posted: a field taking focus starts its input session after the focus change.
            v?.post {
                val holder = v.rootView.findFocus()
                if (!v.hasFocus() && !services.fieldFocused && holder?.onCheckIsTextEditor() != true) v.requestFocus()
            }
        }
        if (v != null && focused && active) v.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        onDispose { v?.viewTreeObserver?.removeOnGlobalFocusChangeListener(listener) }
    }
    DisposableEffect(view, focused) {
        val v = view
        if (focused) v?.onShown = services.onShown
        onDispose { if (focused) v?.onShown = null }
    }
    DisposableEffect(acquisition, view) {
        val v = view
        val attachment = v?.let {
            acquisition.attach(
                connect = { source -> it.setSource(source); visualAttached = true },
                disconnect = {
                    visualAttached = false
                    visualAttachment = null
                    services.typing.unbind(target)
                    it.input = null
                    it.setSource(null)
                },
                onFrame = it::invalidateFrame,
            )
        }
        visualAttachment = attachment
        onDispose { attachment?.close() }
    }
    val ready = active && hostOnline && status.restored && status.stream == DataStream.State.Live && !status.exited && !exited
    DisposableEffect(view, target, focused, ready, visualAttached) {
        val v = view
        val binding = if (focused && visualAttached && v != null) services.typing.bind(target, ready) else null
        if (v != null) v.input = binding?.let { inputBinding ->
            object : TerminalInput {
                override fun onText(text: String) = services.typing.text(inputBinding, text)
                override fun onKey(event: KeyEvent): Boolean {
                    if (!services.typing.accepts(inputBinding)) return true
                    if (event.keyCode == KeyEvent.KEYCODE_P && event.isShiftPressed && (event.isCtrlPressed || event.isMetaPressed)) {
                        if (event.repeatCount == 0) services.onCommands()
                        return true
                    }
                    // ⌘; is Last Pane (tmux's prefix ;). ⌘ combinations never reach a shell.
                    if (event.keyCode == KeyEvent.KEYCODE_SEMICOLON && event.isMetaPressed && !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed) {
                        if (event.repeatCount == 0) services.onLastPane()
                        return true
                    }
                    return services.typing.key(inputBinding, event)
                }
                override fun onBackspace(count: Int) = services.typing.backspace(inputBinding, count)
                override fun onPaste() { if (services.typing.accepts(inputBinding)) services.uploads.paste(ref) }
                override fun onContent(content: InputContentInfoCompat): Boolean {
                    if (!services.typing.accepts(inputBinding)) return false
                    services.uploads.upload(ref, listOf(content.contentUri)) { content.releasePermission() }
                    return true
                }
            }
        }
        onDispose { binding?.close(); v?.input = null }
    }
    LaunchedEffect(visualAttachment, focused, view) {
        visualAttachment?.setPriority(if (focused) PanePriority.Focused else PanePriority.Visible)
        view?.fullRate = focused
    }
    // Haptic only when the program exits while the pane is open.
    LaunchedEffect(pane) {
        pane.status.map { it.exited }.distinctUntilChanged().drop(1).collect { if (it) services.haptics.exited() }
    }

    Box(modifier) {
        // A replacement terminal gets a new view. Effects above own its visual and input handles.
        key(pane) { AndroidView(
            factory = { ctx ->
                TerminalView(ctx).apply {
                    setFont(font)
                    setBackground(theme.background)
                    view = this
                }
            },
            update = { v ->
                v.setFont(font)
                v.setBackground(theme.background)
                v.anchorBottom = settings.anchor == Settings.Anchor.Bottom
                v.shader = when (settings.shader) {
                    Settings.Shader.None -> null
                    Settings.Shader.Crt -> BuiltInShaders.CRT
                    Settings.Shader.Bloom -> BuiltInShaders.Bloom
                    Settings.Shader.CursorGlow -> BuiltInShaders.CursorGlow
                    Settings.Shader.Custom -> settings.customShader.ifBlank { null }
                    else -> settings.shader.asset?.let { BuiltInShaders.asset(v.context, it) }
                }
                v.autocorrect = settings.autocorrect
                v.hardwareRepeat = HardwareRepeat(settings.keyRepeat, settings.keyRepeatDelayMillis, settings.keyRepeatIntervalMillis, settings.repeatHeldLetters)
                v.setBlink(if (focused) settings.blink.millis() else 0)
                v.setAppearance(Appearance(selection = selection.range, focused = focused, faded = faded, lightTheme = !theme.dark, improveContrast = settings.improveContrast,
                    textPalette = if (settings.forcePalette) theme.ansi.map { it and 0xFFFFFF } else emptyList(),
                    cursorStyle = if (settings.forceCursor) when (settings.cursorStyle) {
                        Settings.CursorStyle.Bar -> 0
                        Settings.CursorStyle.Block -> 1
                        Settings.CursorStyle.Underline -> 2
                    } else null))
            },
            modifier = Modifier.fillMaxSize(),
        ) }
        Box(
            Modifier.fillMaxSize().paneGestures(
                rex, ref, pane, { view }, settings.smoothScrolling,
                onTap = { at ->
                    if (selection.range != null) { selection.clear(); return@paneGestures }
                    val v = view ?: return@paneGestures
                    val wasFocused = focusedNow
                    scope.launch {
                        val col = v.columnAt(at.x)
                        val row = v.rowAt(at.y)
                        val url = pane.linkUnder(col, row)
                        if (url != null) link = PressedLink(url, at)
                        else {
                            val rows = v.cell?.let { (v.height / it.height).coerceAtLeast(1) } ?: 1
                            val cellRow = row.coerceIn(0, rows - 1)
                            val mouse = pane.geometry.modes.mouseTracking
                            if (mouse) rex.click(ref.host, ref.session, ref.block, col, cellRow)
                            else if (wasFocused) placeCursor(rex, ref, pane, col, row)
                            onTap()
                            // A click on a mouse control (OpenCode's new tab) is not a request to type.
                            if (!mouse || pane.geometry.cursor?.row == cellRow) {
                                services.keyboardWanted = true
                                v.showKeyboard()
                            }
                        }
                    }
                },
                onLongPress = { at ->
                    val v = view ?: return@paneGestures
                    scope.launch {
                        pane.wordUnder(v.columnAt(at.x), v.rowAt(at.y))?.let {
                            services.haptics.key()
                            selection.all = false
                            selection.range = it
                        }
                    }
                },
            ),
        )
        view?.let { v -> CopyFlashLayer(copied, pane, v) }
        if (settings.copyOnSelection) LaunchedEffect(selection.range) {
            val range = selection.range ?: return@LaunchedEffect
            delay(COPY_ON_SELECT_SETTLE_MILLIS)
            pane.awaitText(range.first, range.second)?.takeIf { it.isNotEmpty() }?.let(context::copyText)
        }
        view?.let { v ->
            SelectionLayer(
                selection, pane, v,
                onCopy = {
                    val range = selection.range
                    val all = selection.all
                    selection.clear()
                    if (settings.copyAnimation) copied.flash(range)
                    scope.launch {
                        val text = if (all) rex.api(ref.host)?.let { runCatching { it.plainText(ref.session, ref.block) }.getOrNull() }
                        else range?.let { pane.awaitText(it.first, it.second) }
                        if (!text.isNullOrEmpty()) context.copyText(text)
                    }
                },
                onPaste = { selection.clear(); services.uploads.paste(ref) },
                onSelectAll = { scope.launch { pane.awaitAll()?.let { selection.range = it; selection.all = true } } },
            )
        }
        preview?.let { path -> FilePreview(rex, ref, path) { preview = null } }
        link?.let { pressed ->
            val file by produceState<FileCheck?>(if (isFilePath(pressed.url)) FileCheck.Checking else null, pressed) {
                if (value != null) value = checkFile(rex, ref, pressed.url)
            }
            LinkMenu(
                pressed, file, appLinks = settings.appLinks == Settings.AppLinks.AskFirst,
                onOpen = { link = null; context.openLink(pressed.url) },
                onPreview = { link = null; preview = pressed.url },
                onCopy = { link = null; context.copyText(pressed.url) },
                onSelectText = {
                    link = null
                    val v = view ?: return@LinkMenu
                    scope.launch { pane.wordUnder(v.columnAt(pressed.at.x), v.rowAt(pressed.at.y))?.let { selection.range = it } }
                },
                onDismiss = { link = null },
            )
        }
        // A state shows only after it has lasted a while. Returning to the app restarts the wait, so a
        // reconnect after a background stay stays quiet unless it really lasts.
        val raw = paneState(status, hostOnline)
        val resumed = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value.isAtLeast(Lifecycle.State.RESUMED)
        var shown by remember { mutableStateOf<PaneState?>(null) }
        LaunchedEffect(raw, resumed) {
            if (!resumed) { shown = null; return@LaunchedEffect }
            if (raw == null || raw == PaneState.AttachFailed) { shown = raw; return@LaunchedEffect }
            delay(if (raw is PaneState.Reconnecting) RECONNECT_GRACE_MILLIS else PILL_DELAY_MILLIS)
            shown = raw
        }
        shown?.let { state ->
            BoxWithConstraints(Modifier.matchParentSize()) {
                PaneStatePill(
                    state,
                    onRetry = { pane.retry(); if (!hostOnline) rex.reconnect(ref.host) },
                    compact = maxWidth < COMPACT_PILL_WIDTH,
                )
            }
        }
        if (status.exited || exited) ExitedBanner(status, onClosePane)
    }
}

private fun Settings.CursorStyle.shape() = when (this) {
    Settings.CursorStyle.Block -> CursorShape.Block
    Settings.CursorStyle.Bar -> CursorShape.Bar
    Settings.CursorStyle.Underline -> CursorShape.Underline
}

private fun Settings.Blink.millis() = when (this) {
    Settings.Blink.Off -> 0
    Settings.Blink.Slow -> 900
    Settings.Blink.Normal -> 600
    Settings.Blink.Fast -> 350
}

internal fun TerminalTheme.toColors() = TerminalColors(
    foreground = foreground and 0xFFFFFF,
    background = background and 0xFFFFFF,
    cursor = cursor and 0xFFFFFF,
    palette = (ansi + extended).map { it and 0xFFFFFF },
    dark = dark,
)

private const val PILL_DELAY_MILLIS = 800L
private const val RECONNECT_GRACE_MILLIS = 2_500L
// Narrower panes (Fold tiles) show the pill's button without its label, so nothing is cut off.
private val COMPACT_PILL_WIDTH = 300.dp

// Taps place the cursor only on a shell prompt's line, as a desktop click does. Full-screen and
// mouse-aware programs take taps their own way, and nothing happens while scrolled back.
private fun placeCursor(rex: Rex, ref: PaneRef, pane: dev.mahali.hatch.engine.Pane, col: Int, row: Int) {
    val g = pane.geometry
    val cursor = g.cursor ?: return
    if (g.modes.alternateScreen || g.modes.mouseTracking || g.scroll.scrolledBack || row != cursor.row) return
    val steps = col - cursor.column
    // ponytail: same line only, one arrow per column; a wrapped multi-line input or wide
    // characters would need the shell's own idea of the line.
    val key = dev.mahali.hatch.engine.namedKey(if (steps > 0) "arrow_right" else "arrow_left")
    repeat(kotlin.math.abs(steps).coerceAtMost(MAX_PLACE_STEPS)) { rex.key(ref.host, ref.session, ref.block, key) }
}

private const val MAX_PLACE_STEPS = 400

// A shell keeps its prompt line above the keyboard. A full-screen program (alternate screen or mouse
// tracking: OpenCode, vim) keeps its input line and hints; keeping the whole bottom edge pushed the input up under the header.
// With no cursor, the bottom edge.
internal fun keyboardRows(fullScreen: Boolean, cursorRow: Int?, rows: Int): Int? = when {
    fullScreen && cursorRow != null -> minOf(rows, cursorRow + 1 + ROWS_UNDER_INPUT)
    fullScreen -> rows
    cursorRow != null -> cursorRow + 1
    else -> null
}

private const val ROWS_UNDER_INPUT = 4

// The settle delay stops dragging a handle from copying on every move.
private const val COPY_ON_SELECT_SETTLE_MILLIS = 500L
