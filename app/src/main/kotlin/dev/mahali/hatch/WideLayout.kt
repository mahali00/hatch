package dev.mahali.hatch

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.ButtonSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import dev.mahali.hatch.design.HatchPillButton
import dev.mahali.hatch.design.HatchType
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.ListMetrics
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.TerminalTheme
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.feature.sessions.SessionsScreen
import dev.mahali.hatch.feature.terminal.AreaMotion
import dev.mahali.hatch.feature.terminal.TerminalScreen
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Composable


internal fun WideLayout(app: HatchApp, settings: Settings, theme: TerminalTheme, nav: AppNavigator, tabStates: SaveableStateHolder, onProgramBackground: (Int) -> Unit) {
    val target = nav.target
    val sidebarShown = nav.sidebarShown
    val density = LocalDensity.current
    var width by remember { mutableFloatStateOf(settings.sidebarWidthDp.toFloat()) }
    // Width before clamping, so a drag far past the narrowest can close the sidebar.
    var pulled by remember { mutableFloatStateOf(width) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // At most 45% of the window: on a Fold's 750 dp inner screen the terminal keeps about 470 dp.
        val windowWidth = maxWidth
        val maxSidebar = maxWidth.value * SIDEBAR_MAX_FRACTION
        // Slot animates between 0 and its width; content keeps full width. Read only in layout and draw,
        // so the slide recomposes nothing per frame.
        // The toggle's heavy work runs in a frame of its own, before the slide: in the first slide frame it was a 15-25 ms hitch.
        var slideTo by remember { mutableStateOf(sidebarShown) }
        var moving by remember { mutableStateOf(false) }
        val shownState = animateFloatAsState(if (slideTo) 1f else 0f, SidebarSpring, label = "sidebar")
        LaunchedEffect(sidebarShown) {
            if (slideTo != sidebarShown) {
                moving = true
                withFrameNanos {}
                withFrameNanos {}
                slideTo = sidebarShown
            }
            val end = if (slideTo) 1f else 0f
            snapshotFlow { shownState.value }.first { it == end }
            // One more frame, so the terminal's recomposition for the last move can't delay it.
            withFrameNanos {}
            moving = false
        }
        val shown by shownState
        val full = width.coerceIn(minOf(260f, maxSidebar), maxSidebar)
        val fullWidthPx = rememberUpdatedState(with(density) { full.dp.toPx() })
        val slide = remember { AreaMotion.Slide { fullWidthPx.value * shownState.value } }
        // Read in an effect: passed as a value, the switch back to Still recomposed the terminal (5-8 ms on the Fold).
        val wanted = rememberUpdatedState(sidebarShown)
        val motion = remember { { if (moving || slideTo != wanted.value) slide else AreaMotion.Still } }
        // Composed while hidden so showing it doesn't rebuild the list (23 ms on the Fold); paused while hidden.
        val hidden by remember { derivedStateOf { shownState.value == 0f && !nav.sidebarShown } }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxHeight().clipToBounds().then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
                    .swipe(density) { dx -> if (dx < 0) nav.showSidebar(false) }
                    .layout { measurable, constraints ->
                    val fullPx = full.dp.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = fullPx, maxWidth = fullPx))
                    val visible = (fullPx * shown).roundToInt()
                    layout(visible, placeable.height) { placeable.place(visible - fullPx, 0) }
                },
            ) {
                SessionsScreen(
                    app.rex.state, app.settings, app.actions, onOpenTab = { nav.open(it) }, onOpenSettings = { nav.openSettings() },
                    onEditHost = { nav.editingHost = it }, onOpenPane = { ref, block -> nav.openPane(app.rex, ref, block) },
                    modifier = Modifier.fillMaxSize(), sidebar = true, selected = target, paused = hidden,
                )
            }
            // The terminal resizes at slide start, so its panes have re-wrapped by the slide's end; moving it by layer avoids relayout per frame.
            val end = if (sidebarShown) 1f else 0f
            val terminalColor = chrome.workspace
            Box(Modifier.fillMaxSize().drawBehind { drawRect(terminalColor, topLeft = Offset(slide.offsetX(), 0f)) })
            Box(Modifier.fillMaxHeight().width(windowWidth - (full * end).dp).graphicsLayer { translationX = slide.offsetX() }) {
                if (target != null) {
                    tabStates.SaveableStateProvider(target.toString()) {
                        TerminalScreen(app.rex, settings, app.actions, theme, target, wide = true,
                            onBack = { nav.tabGone(target) }, onOpenTab = { nav.open(it) }, onCommands = { nav.commandsShown = true },
                            onTabGone = nav::tabGone,
                            motion = motion, onProgramBackground = onProgramBackground)
                    }
                } else NoTab(sidebarShown = nav.sidebarShown, onShowSidebar = { nav.showSidebar(true) })
            }
        }
        if (!hidden) {
            Box(
                Modifier.offset {
                    IntOffset((full.dp.roundToPx() * shown).roundToInt() - SidebarResizeTarget.roundToPx() / 2, 0)
                }.width(SidebarResizeTarget).fillMaxHeight().draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        pulled += with(density) { delta.toDp().value }
                        width = pulled.coerceIn(260f, maxSidebar)
                    },
                    onDragStarted = { pulled = width },
                    onDragStopped = {
                        if (pulled < 260f - SwipeDistance.value) nav.showSidebar(false)
                        app.settings.update { it.copy(sidebarWidthDp = width.toInt()) }
                    },
                ).semantics { contentDescription = "Resize sidebar" },
            )
        }
        if (hidden) {
            // Android lets an app take at most 200 dp of an edge; the rest stays Back.
            Box(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .size(EdgeSwipeWidth, EdgeSwipeHeight).systemGestureExclusion()
                    .swipe(density) { dx -> if (dx > 0) nav.showSidebar(true) },
            )
        }
        val colors = chrome
        HeaderButton(
            HatchIcons.Sidebar,
            if (sidebarShown) "Hide sidebar" else "Show sidebar",
            { nav.showSidebar(!sidebarShown) },
            Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = ListMetrics.Sidebar.inset, top = Dimens.SidebarToggleTop)
                .drawBehind {
                    drawRect(colors.workspace)
                },
            size = ButtonSize.Toolbar,
        )
    }
}

private val SidebarResizeTarget = 16.dp
private val EdgeSwipeWidth = 12.dp
private val EdgeSwipeHeight = 200.dp

private val SwipeDistance = 48.dp

private fun Modifier.swipe(density: androidx.compose.ui.unit.Density, onSwipe: (Float) -> Unit): Modifier = pointerInput(Unit) {
    var travel = 0f
    detectHorizontalDragGestures(
        onDragStart = { travel = 0f },
        onDragEnd = { if (kotlin.math.abs(travel) > with(density) { SwipeDistance.toPx() }) onSwipe(travel) },
    ) { change, dx -> change.consume(); travel += dx }
}
private const val SIDEBAR_MAX_FRACTION = 0.45f

private val SidebarSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

@Composable
private fun NoTab(sidebarShown: Boolean, onShowSidebar: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Dimens.ScreenInset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(HatchIcons.Sessions, null, Modifier.size(36.dp), tint = chrome.secondary)
        Text("No tab open", Modifier.padding(top = Hatch.space.m), style = HatchType.Title, color = chrome.text)
        Text(
            if (sidebarShown) "Pick a tab in the sidebar, or start one with ⊕." else "Show the sidebar to pick a tab.",
            Modifier.padding(top = Hatch.space.xs), style = HatchType.Callout, color = chrome.secondary, textAlign = TextAlign.Center,
        )
        if (!sidebarShown) Box(Modifier.padding(top = Hatch.space.l)) { HatchPillButton("Show Sidebar", onShowSidebar) }
    }
}
