package dev.mahali.hatch.feature.terminal

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.zIndex
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.TerminalTheme
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.paneFrame
import kotlin.math.roundToInt

// Geometry is read from [stage] during layout and draw, never computed here.
@Composable
internal fun PaneHost(
    stage: TerminalViewport,
    pane: PlacedPane,
    strip: TileStrip,
    scrollable: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onAction: (PaneAction) -> Unit,
    onSelect: () -> Unit,
    onPickUp: () -> Unit,
    lifted: Boolean = false,
    onDrag: (Offset) -> Unit,
    onDrop: (cancelled: Boolean) -> Unit,
    accessibility: () -> List<CustomAccessibilityAction>,
    terminal: @Composable () -> Unit,
) {
    val block = pane.block
    // The strip's gestures outlive recompositions; they call the latest of these.
    val select by rememberUpdatedState(onSelect)
    val pickUp by rememberUpdatedState(onPickUp)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val menu by rememberUpdatedState(onMenu)
    fun origin(): Offset = stage.placedOf(block)?.inner?.let { Offset(it.left - stage.scroll.x, it.top - stage.scroll.y) } ?: Offset.Zero
    val sink by animateFloatAsState(if (lifted) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "pane held")
    Column(
        Modifier.at { stage.placedOf(block)?.inner?.minus(stage.scroll) }
            .graphicsLayer { val s = 1f - LIFT_SCALE * sink; scaleX = s; scaleY = s; alpha = 1f - LIFT_DIM * sink }
            .then(if (pane.focused) Modifier.zIndex(1f).graphicsLayer { translationY = -stage.liftOf(block) } else Modifier)
            .then(if (pane.framed) Modifier.paneFrame(chrome.workspace, chrome.stroke, HatchShapes.PaneRadius) else Modifier)
            .background(chrome.terminal),
    ) {
        if (pane.strip) PaneTitleBar(
            strip, focused = pane.focused, zoomed = false, scrollable = scrollable,
            onAction = onAction, menuOpen = menuOpen, onMenu = onMenu,
            titleModifier = Modifier.paneTitleGestures(
                block,
                onTap = { select() },
                onHold = { pickUp() },
                onMenu = { select(); menu(true) },
                onStart = { at -> drag(origin() + at) },
                onMove = { at -> drag(origin() + at) },
                onEnd = { drop(it) },
            ).semantics { customActions = accessibility() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { terminal() }
    }
}

// Pinned at its grid's size: the box may grow or shrink mid-motion.
@Composable
internal fun PaneTerminal(stage: TerminalViewport, block: BlockId, modifier: Modifier, terminal: @Composable () -> Unit) {
    val drawn = stage.drawnSize(block) ?: return
    val density = LocalDensity.current
    val slot = remember { SlotClip() }
    Box(modifier.clipToBounds().onSizeChanged { slot.size = it }) {
        Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(with(density) { DpSize(drawn.width.toDp(), drawn.height.toDp()) }),
        ) { CompositionLocalProvider(LocalSlotClip provides slot) { terminal() } }
    }
}

// The terminal draws outside Compose's clip, so it clips its own surface to this slot; otherwise a
// divider narrowing the slot let the surface spill into the neighbouring pane.
internal class SlotClip { var size by mutableStateOf<IntSize?>(null) }

internal val LocalSlotClip = staticCompositionLocalOf<SlotClip?> { null }

// Passed on each call: movable content keeps the lambda it was made with.
internal data class PaneEnv(
    val services: PaneServices, val viewport: TerminalViewport, val host: HostId, val session: SessionId, val theme: TerminalTheme, val settings: Settings,
    val hostOnline: Boolean, val exited: Set<BlockId>, val onTap: (BlockId) -> Unit, val onClose: (BlockId) -> Unit,
)

// Made once per block and kept, so page and tile switches carry the terminal over instead of rebuilding it.
internal fun movableTerminal(block: BlockId): @Composable (PaneEnv, Boolean, Boolean) -> Unit =
    movableContentOf { env: PaneEnv, focused: Boolean, faded: Boolean ->
        TerminalPane(
            env.services, env.viewport, PaneRef(env.host, env.session, block), env.theme, env.settings, focused, env.hostOnline,
            exited = block in env.exited, onTap = { env.onTap(block) }, onClosePane = { env.onClose(block) },
            modifier = Modifier.fillMaxSize(), faded = faded,
        )
    }

internal fun Modifier.at(rect: () -> IntRect?): Modifier = layout { measurable, constraints ->
    val r = rect() ?: IntRect.Zero
    val placeable = measurable.measure(Constraints.fixed(r.width.coerceAtLeast(0), r.height.coerceAtLeast(0)))
    layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) { placeable.place(r.left, r.top) }
}

internal operator fun IntRect.minus(o: Offset): IntRect = translate(-o.x.roundToInt(), -o.y.roundToInt())

private const val LIFT_SCALE = 0.04f
private const val LIFT_DIM = 0.25f
