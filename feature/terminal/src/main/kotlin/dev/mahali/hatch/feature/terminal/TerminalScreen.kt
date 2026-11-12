package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.Dimens
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.drawBehind
import dev.mahali.hatch.design.AnimationStyle
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostKind
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.RexActions
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.Title
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.title
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenuCard
import dev.mahali.hatch.design.HatchMenuPopup
import dev.mahali.hatch.design.HatchPill
import dev.mahali.hatch.design.HatchPillText
import dev.mahali.hatch.design.HatchConfirmDialog
import dev.mahali.hatch.design.HatchRenameDialog
import dev.mahali.hatch.design.HatchSpinner
import dev.mahali.hatch.design.TerminalTheme
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.renderer.CellMetrics
import dev.mahali.hatch.renderer.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun TerminalScreen(
    rex: Rex,
    settings: Settings,
    actions: RexActions,
    theme: TerminalTheme,
    target: WindowRef,
    wide: Boolean,
    onBack: () -> Unit,
    onOpenTab: (WindowRef) -> Unit,
    onCommands: () -> Unit,
    onTabGone: (WindowRef, Int) -> Unit,
    modifier: Modifier = Modifier,
    // Read in an effect, so a slide starting or stopping recomposes nothing.
    motion: () -> AreaMotion = { AreaMotion.Still },
    /** The focused pane's program background (OSC 11, else the theme's), when it is shown and when it changes. */
    onProgramBackground: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = key(target) { rememberCoroutineScope() }

    val currentSettings by rememberUpdatedState(settings)
    val haptics = remember(view) { Haptics(view) { currentSettings.haptics } }
    val typing = remember(rex, haptics) { Typing(rex, haptics) }
    val uploads = remember(rex, scope) { Uploads(rex, context.applicationContext, scope) }
    val currentCommands by rememberUpdatedState(onCommands)
    val currentBackground by rememberUpdatedState(onProgramBackground)
    val connection = rex.sync(target.host)
    // The tab's panes stay under a plain fill until the focused one's first frame is on screen.
    var revealed by remember(target) { mutableStateOf(false) }
    var lastPane by remember { mutableStateOf({}) }
    val services = remember(typing, uploads, connection) {
        PaneServices(
            rex, typing, uploads, haptics, connection,
            onCommands = { currentCommands() }, onLastPane = { lastPane() },
            onShown = { revealed = true; currentBackground(it) },
        )
    }
    // Which panes this device sizes and focuses, kept in step with the server (claims, reconnects).
    val lease = remember(target) { rex.lease(target.host, target.session, scope) }
    val colors = remember(theme) { theme.toColors() }

    val back by rememberUpdatedState(onBack)
    val open by rememberUpdatedState(onOpenTab)
    val gone by rememberUpdatedState(onTabGone)
    val currentActions by rememberUpdatedState(actions)
    val operations = remember(rex, target) { rex.tabOperations(target) }
    val make = {
        TabController(
            target, wide, rex.state, scope, operations,
            say = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
            onOpenTab = { open(it) }, onBack = { back() }, onSplit = lease::focusedByServer,
            onTabGone = { source, index -> gone(source, index) },
            onNewTab = { currentActions.newTab(target.host, target.session) },
        )
    }
    val tab = rememberSaveable(target, wide, saver = TabController.saver(make)) { make() }
    SideEffect { lastPane = tab::lastPane }
    val host = tab.host
    val session = tab.session
    val window = tab.window

    // The one owner of where each pane is, the size it is drawn at and the grid the host is told,
    // and how far the focused pane rises for the keyboard. Everything below feeds it or reads it.
    val stage = remember(target) {
        val handler = Handler(Looper.getMainLooper())
        TerminalViewport(SystemClock::uptimeMillis, after = { millis, action ->
            val run = Runnable(action)
            handler.postDelayed(run, millis)
            ({ handler.removeCallbacks(run) })
        })
    }
    // Read here, in composition, so a change to any of them recomposes and reaches the viewport.
    // Read only inside the SideEffect they were not tracked: a dragged column width never arrived.
    val shownLayout = window?.layout
    val shownMode = tab.mode
    val shownCurrent = tab.current
    val shownWidths = tab.columnWidths
    SideEffect {
        tab.reconcileSelection()
        stage.layout(shownLayout, shownMode, shownCurrent, shownWidths)
    }
    val currentMotion by rememberUpdatedState(motion)
    LaunchedEffect(stage) { snapshotFlow { currentMotion() }.collect { stage.motion(it) } }
    fun select(block: BlockId) {
        if (block == tab.current) stage.reveal() else tab.chosen = block
    }

    val pager = key(target) {
        rememberPagerState(initialPage = tab.panes.indexOf(tab.current).coerceAtLeast(0)) { tab.panes.size }
    }
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    var swipeBoundary by remember(pager) { mutableStateOf<PagerBoundary?>(null) }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(dragging, pager.isScrollInProgress, pager.settledPage) }.collect { (drag, scrolling, page) ->
            if (drag && swipeBoundary == null) swipeBoundary = tab.beginSwipe()
            if (!drag && !scrolling) {
                swipeBoundary?.let { tab.swiped(it, page) }
                swipeBoundary = null
            }
        }
    }

    val requestedPane by rex.requestedPane.collectAsState()
    LaunchedEffect(requestedPane, tab.panes) {
        val request = requestedPane?.takeIf { it.target.window == target } ?: return@LaunchedEffect
        if (request.target.block in tab.panes) {
            tab.pending = request.target.block
            rex.consumePaneRequest(request)
        }
    }
    var minimapPoke by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab.transition, tab.panes, tab.current, tab.tiled, swipeBoundary == null) {
        val request = tab.transition
        // A gesture cancels the old animation, not the other way around. A new request may interrupt it.
        if (request == null && swipeBoundary != null) return@LaunchedEffect
        val block = request?.block?.takeIf { it in tab.panes } ?: tab.current ?: return@LaunchedEffect
        if (request != null && request.block == block) tab.activate(request)
        if (!tab.tiled && request != null && request.block == block) {
            minimapPoke++
            pager.animateScrollToPage(tab.panes.indexOf(block), animationSpec = SettleSpec)
        } else if (!tab.tiled) {
            val index = tab.panes.indexOf(block)
            if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
        }
        // Cancellation never acknowledges a request. An obsolete completion cannot clear a new one.
        if (request != null && request.block == block) tab.complete(request)
    }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(foreground) { tab.foreground(foreground) }

    val settled = tab.settled(pager.isScrollInProgress, dragging, pager.targetPage, swipeBoundary)
    PaneLeaseEffects(lease, tab, stage, settled, colors, active)

    LaunchedEffect(target, connection, services) {
        followSignals(rex, target, { tab.panes }, context, haptics) { block, url -> services.offeredLink = OfferedLink(block, url) }
    }

    // The keyboard never resizes the terminal, as on iOS: the host's grid stays put, so nothing re-wraps.
    // KeyboardInset goes to the viewport every animation frame; the focused terminal slides up just enough.
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val imeSource = WindowInsets.imeAnimationSource
    val navigation = WindowInsets.navigationBars
    val navBottom = with(density) { navigation.getBottom(this).toDp() }
    val keyBarPx = with(density) { KeyBarHeight.toPx() }
    LaunchedEffect(stage) {
        snapshotFlow {
            KeyboardInset(
                ime.getBottom(density).toFloat(), maxOf(imeTarget.getBottom(density), imeSource.getBottom(density)).toFloat(),
                keyBarPx, navigation.getBottom(density).toFloat(),
            )
        }.collect { stage.keyboard(it) }
    }
    val keyboardUp by remember(stage) { derivedStateOf { stage.inset.visible } }
    // The keyboard closing (by back, the dismiss key or the system) ends the user's request for it.
    LaunchedEffect(keyboardUp, services) { if (!keyboardUp) services.keyboardWanted = false }
    val cell = remember(settings.fontFamily, settings.fontSize, settings.lineHeight) { CellMetrics.of(Fonts.terminal(context, settings)) }
    val paneGap = Dimens.PaneGap
    SideEffect {
        stage.metrics(ViewportMetrics(density.density, cell.width, cell.height, with(density) { StripHeight.roundToPx() }, TerminalView.sidePadding(context), paneGap))
    }

    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var finding by rememberSaveable(target) { mutableStateOf(false) }
    var findQuery by rememberSaveable(target) { mutableStateOf("") }
    val findBlock = tab.current.takeIf { finding }
    val findAcquisition = remember(rex, target, findBlock, connection) {
        findBlock?.let { rex.acquire(target.host, target.session, it) }
    }
    val findPane = findAcquisition?.pane
    DisposableEffect(findAcquisition) {
        onDispose {
            findPane?.find(null)
            findAcquisition?.close()
        }
    }
    LaunchedEffect(findPane, findQuery) {
        delay(FIND_DEBOUNCE_MILLIS)
        findPane?.find(findQuery)
    }
    val findInfo by remember(findPane) { findPane?.search ?: MutableStateFlow(null) }.collectAsState()
    var rename by remember { mutableStateOf<Rename?>(null) }
    var closing by remember { mutableStateOf<Confirm?>(null) }
    val gestures = remember(target) { CanvasGestures() }

    val current = tab.current
    val currentBlock = current?.let { host?.blocksById?.get(it) }
    val pull = remember(density) { with(density) { PullToSplit(PULL_THRESHOLD.toPx(), PULL_CAP.toPx(), PULL_RELEASE_SPEED.toPx()) } }
    SideEffect { pull.onSplit = tab::splitCurrent }
    val pulling by remember(pull) { derivedStateOf { pull.pull > 0f } }
    LaunchedEffect(pull) {
        snapshotFlow { pull.armed }.drop(1).collect {
            if (it) haptics.armed() else haptics.disarmed()
        }
    }
    fun paneAction(block: BlockId, action: PaneAction) {
        select(block)
        tab.action(block, action)
    }
    fun strip(block: BlockId): TileStrip {
        val b = host?.blocksById?.get(block)
        return TileStrip(b?.program ?: ProgramKey.Terminal, b?.title(settings.preferGeneratedTitles) ?: Title("Terminal"))
    }
    val tabIndex = session?.windows?.indexOf(target.window) ?: -1
    val menuActions = MenuActions(
        commands = onCommands,
        find = { finding = true },
        splitPane = tab::splitCurrent,
        closePane = { tab.current?.let(tab::close) },
        lastPane = if (tab.hasLastPane) tab::lastPane else null,
        newTab = tab::newTab,
        // The server's default label is not a name the user gave, so start from the shown title.
        renameTab = { rename = Rename("Rename Tab", window?.let { w -> host?.title(w, settings.preferGeneratedTitles)?.primary }.orEmpty()) { actions.renameTab(target, it) } },
        moveTab = { forward -> actions.moveTab(target, forward) },
        closeTab = {
            closing = Confirm("Close Tab?", "Every pane in this tab closes, and the programs running in them end.", "Close Tab") {
                actions.closeTab(target)
                tab.tabGone()
            }
        },
        renameSession = { rename = Rename("Rename Session", session?.label.orEmpty()) { actions.renameSession(target.host, target.session, it) } },
        closeSession = {
            closing = Confirm("Close Session?", "Every tab in this session closes, and the programs running in them end.", "Close Session") {
                actions.closeSession(target.host, target.session)
                tab.leave()
            }
        },
    )
    val terminalMenu = @Composable {
        TerminalMenu(
            menu, canMoveBack = tabIndex > 0, canMoveForward = tabIndex in 0 until (session?.windows?.size ?: 0) - 1, menuActions,
            layout = if (tab.panes.size > 1) tab.view else null,
            onLayout = { mode ->
                tab.view = mode
                if (mode == PaneView.Scroll) Toast.makeText(context, "Use two fingers to move the workspace", Toast.LENGTH_SHORT).show()
            },
        ) { menu = false }
    }

    val strips by remember(stage) { derivedStateOf { stage.composed.value.any { it.strip } } }
    // The same set and env while nothing they hold changes: a new env every pass (this screen
    // recomposes on any title) recomposed every pane's terminal.
    val exited by remember(tab) { derivedStateOf { tab.host?.blocksById.orEmpty().filterValues { it.exited }.keys } }
    val hostOnline = tab.hostOnline
    val env = remember(services, stage, target, theme, settings, hostOnline, exited, tab, lease) {
        PaneEnv(
            services, stage, target.host, target.session, theme, settings, hostOnline, exited,
            onTap = { block -> lease.touched(block); if (tab.tiled) select(block) },
            onClose = tab::close,
        )
    }
    val terminals = remember(target) { HashMap<BlockId, @Composable (PaneEnv, Boolean, Boolean) -> Unit>() }
    // A pane's view binds to its block once; a slot handed another block gets a new pane, so typing never goes to the wrong terminal.
    @Composable
    fun Terminal(pane: PlacedPane) = key(pane.block) {
        PaneTerminal(stage, pane.block, Modifier.fillMaxSize()) { terminals.getOrPut(pane.block) { movableTerminal(pane.block) }(env, pane.focused, pane.faded) }
    }

    Box(modifier.fillMaxSize().background(chrome.workspace).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
        Column(Modifier.fillMaxSize()) {
            TerminalHeader(
                program = currentBlock?.program ?: ProgramKey.Terminal,
                title = currentBlock?.title(settings.preferGeneratedTitles) ?: window?.let { host?.title(it, settings.preferGeneratedTitles) } ?: Title("Terminal"),
                session = sessionLine(session?.label, target.session),
                onBack = if (wide) null else tab::leave,
                onSession = { sheet = true },
                onMenu = { menu = true },
                // Drawn above the terminal: rows that slide up with the keyboard go under it.
                modifier = Modifier.zIndex(1f).background(chrome.workspace).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                menu = terminalMenu,
            )
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(bottom = SwitcherRowHeight + navBottom)
                    .drawWithContent {
                        val content = this
                        clipRect(bottom = (size.height - stage.inset.overlapPx).coerceAtLeast(0f)) { content.drawContent() }
                    },
            ) {
                // Only the focused tile rides up with the keyboard, so other panes don't slide under the header.
                Box(Modifier.fillMaxSize().graphicsLayer {
                    translationY = if (tab.tiled) 0f else -stage.liftOf(tab.current)
                }) {
                    // The viewport commits sizes once per change, never once per animation frame.
                    Box(Modifier.fillMaxSize().onSizeChanged {
                        stage.area(it)
                        tab.area = with(density) { DpSize(it.width.toDp(), it.height.toDp()) }
                    }) {
                        if (!tab.parked && window != null) {
                            val layout = window.layout
                            val scrollable = tab.view == PaneView.Scroll
                            if (tab.tiled && layout != null) PaneCanvas(
                                stage, scrollable, gestures, Modifier.fillMaxSize(),
                            ) {
                                PaneTiles(
                                    stage, scrollable, gestures,
                                    strip = ::strip,
                                    onSelect = ::select,
                                    onPickUp = haptics::pickUp,
                                    onDropTarget = haptics::dropTarget,
                                    onDropped = haptics::dropped,
                                    onAction = ::paneAction,
                                    onResize = tab::resize,
                                    onColumnResize = tab::resizeColumn,
                                    onDrop = tab::drop,
                                    modifier = Modifier.fillMaxSize(),
                                ) { Terminal(it) }
                            } else Column(Modifier.fillMaxSize()) {
                                PanePager(pager, tab.panes, pull, Modifier.weight(1f)) { block ->
                                    stage.composed.value.firstOrNull { it.block == block }?.let { Terminal(it) }
                                }
                            }
                            if (!tab.tiled && layout != null && (tab.panes.size > 1 || pulling)) {
                                MinimapOverlay(
                                    layout, tab.panes,
                                    { pager.currentPage + pager.currentPageOffsetFraction },
                                    pager.isScrollInProgress, minimapPoke,
                                    Modifier.align(Alignment.TopEnd).padding(
                                        top = if (strips) StripHeight + Hatch.space.s else Hatch.space.s,
                                        end = Hatch.space.m,
                                    ),
                                )
                            }
                        } else if (window == null) {
                            HatchPill(Modifier.align(Alignment.TopCenter).padding(top = Hatch.space.m)) {
                                HatchSpinner()
                                HatchPillText("Opening terminal", chrome.text)
                            }
                        }
                        uploads.pill?.let {
                            UploadStatus(it, uploads::dismiss, Modifier.align(Alignment.TopCenter).padding(top = Hatch.space.m))
                        }
                    }
                }
                PaneReveal(target, revealed)
                if (finding) FindBar(
                    findQuery, { findQuery = it }, findInfo,
                    onOlder = { findPane?.findNext(older = true) },
                    onNewer = { findPane?.findNext(older = false) },
                    onClose = {
                        finding = false
                        findQuery = ""
                        services.fieldFocused = false
                    },
                    onFocus = { services.fieldFocused = it },
                    modifier = Modifier.align(Alignment.TopEnd).padding(
                        top = if (strips) StripHeight + Hatch.space.s else Hatch.space.s,
                        start = Hatch.space.m, end = Hatch.space.m,
                    ),
                )
                val blocked = currentBlock?.status?.values?.any { it.state == ProgramState.Blocked } == true
                PromptChoices(
                    rex, current?.takeIf { blocked }?.let { PromptTarget(target, it) }, shown = !keyboardUp,
                    Modifier.align(Alignment.BottomCenter).padding(Hatch.space.m),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(navigation)
                .graphicsLayer { translationY = -stage.inset.raisePx }.background(chrome.workspace),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val generated = settings.preferGeneratedTitles
            // An equal list keeps its instance, so the switcher skips the passes that change nothing it shows.
            val switcher by remember(tab, generated) {
                derivedStateOf { tab.panes.mapNotNull { b -> tab.host?.blocksById?.get(b)?.let { SwitcherPane(b, it.program, it.title(generated).primary) } } }
            }
            Row(Modifier.fillMaxWidth().height(SwitcherRowHeight).padding(horizontal = Hatch.space.s),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                PaneSwitcher(
                    if (tab.tiled) emptyList() else switcher, current,
                    position = { if (tab.tiled) 0f else pager.currentPage + pager.currentPageOffsetFraction },
                    pull = { pull.progress }, armed = pull.armed, keyboardVisible = keyboardUp,
                    onSelect = { tab.pending = it }, onSplit = tab::splitCurrent,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // Not while typing in the find field: its keys are the terminal's.
            if (keyboardUp && !services.fieldFocused) {
                KeyBar(typing, settings, haptics, host?.host?.kind in MAC_HOSTS, onDismissKeyboard = {
                    current?.let { rex.rememberKeyboard(target.host, it, false) }
                    services.keyboardWanted = false
                    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
                })
            }
        }
    }

    if (sheet) SessionSheet(
        rex.state.collectAsState().value,
        target,
        settings.preferGeneratedTitles,
        onOpenTab = {
            sheet = false
            tab.openTab(it)
        },
        onNewTab = {
            sheet = false
            tab.newTab()
        },
        onDismiss = { sheet = false },
    )
    closing?.let { dialog -> HatchConfirmDialog(dialog.title, dialog.message, dialog.confirm, dialog.apply) { closing = null } }
    rename?.let { dialog ->
        HatchRenameDialog(
            dialog.title,
            dialog.current,
            onSave = { name ->
                rename = null
                dialog.apply(name)
            },
            onDismiss = { rename = null },
        )
    }
}

// A new tab's panes fade in from the canvas once the focused one has drawn, so text doesn't pop in a few
// frames after the switch. A fill, not a crossfade of two tabs: SurfaceViews ignore their parents' alpha.
@Composable
private fun BoxScope.PaneReveal(target: WindowRef, revealed: Boolean) {
    val motion = Hatch.motion
    val cover = remember(target) { Animatable(if (motion.style == AnimationStyle.Off) 0f else 1f) }
    LaunchedEffect(cover, revealed) {
        if (cover.value == 0f) return@LaunchedEffect
        // Never held for long: an offline host or a slow restore shows its pane state instead.
        if (!revealed) delay(REVEAL_WAIT_MILLIS)
        cover.animateTo(0f, motion.spec(motion.quick))
    }
    val fill = chrome.workspace
    // Drawn, not composed, per animation frame.
    Box(Modifier.matchParentSize().drawBehind { cover.value.takeIf { it > 0f }?.let { drawRect(fill, alpha = it) } })
}

@Composable
private fun MinimapOverlay(layout: Layout, pages: List<BlockId>, position: () -> Float, paging: Boolean, poke: Int, modifier: Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(paging, poke) {
        if (paging) shown = true
        else if (shown || poke > 0) {
            shown = true
            delay(MINIMAP_OUT_MILLIS)
            shown = false
        }
    }
    AnimatedVisibility(shown, modifier, enter = fadeIn(tween(MINIMAP_FADE)), exit = fadeOut(tween(MINIMAP_FADE))) { Minimap(layout, pages, position) }
}

private val MAC_HOSTS = setOf(HostKind.MacBook, HostKind.MacMini, HostKind.MacStudio)

private const val MINIMAP_OUT_MILLIS = 1_300L
private const val REVEAL_WAIT_MILLIS = 300L
private const val MINIMAP_FADE = 150
private val PULL_THRESHOLD = 120.dp
/** A pull that lets go faster than this (per second) was a swipe, not a pull: it does not split. */
private val PULL_RELEASE_SPEED = 600.dp
private val PULL_CAP = 56.dp

public fun terminalControlsHeight(keyboardVisible: Boolean): Dp =
    SwitcherRowHeight + if (keyboardVisible) KeyBarHeight else 0.dp

// Typing a word searches once, not once per letter.
private const val FIND_DEBOUNCE_MILLIS = 120L
