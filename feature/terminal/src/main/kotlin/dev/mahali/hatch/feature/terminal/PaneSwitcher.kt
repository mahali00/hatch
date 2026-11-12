package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.design.ProgramGlyph
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.chrome
import kotlin.math.roundToInt

internal data class SwitcherPane(val block: BlockId, val program: ProgramKey, val title: String)

@Composable
internal fun PaneSwitcher(
    panes: List<SwitcherPane>,
    current: BlockId?,
    position: () -> Float,
    pull: () -> Float,
    armed: Boolean,
    keyboardVisible: Boolean,
    onSelect: (BlockId) -> Unit,
    onSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read only during layout and draw: reading it here recomposed the switcher every frame of the keyboard slide.
    val compact = animateFloatAsState(if (keyboardVisible) 1f else 0f, tween(150), label = "pane switcher size")
    // Smaller segments keep long rows light; the touch target stays the full row height.
    val segmentWidth = { lerp(32.dp, 28.dp, compact.value) }
    val segmentHeight = { lerp(24.dp, 20.dp, compact.value) }
    // At 18 dp the glyph filled its 24 dp segment.
    val glyphSize = Hatch.size.iconSmall
    Row(modifier.height(SwitcherHeight), horizontalArrangement = Arrangement.spacedBy(Hatch.space.s), verticalAlignment = Alignment.CenterVertically) {
        if (panes.size > 1) {
            val scroll = rememberScrollState()
            val index = panes.indexOfFirst { it.block == current }
            LaunchedEffect(index, panes.size, scroll.viewportSize) {
                if (index < 0 || scroll.maxValue == 0) return@LaunchedEffect
                val segment = scroll.maxValue.let { (it + scroll.viewportSize) / panes.size }
                scroll.animateScrollTo((index * segment - (scroll.viewportSize - segment) / 2).coerceIn(0, scroll.maxValue))
            }
            val card = chrome.card
            Box(
                Modifier.weight(1f, fill = false).height(SwitcherHeight)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val fade = EdgeFade.toPx()
                        if (scroll.value > 0) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = fade), blendMode = BlendMode.DstIn)
                        if (scroll.value < scroll.maxValue) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade), blendMode = BlendMode.DstIn)
                        // Drawn behind the icons, so only the icons fade at the ends.
                        val inset = (size.height - segmentHeight().toPx()) / 2
                        drawRoundRect(card, Offset(0f, inset), Size(size.width, size.height - 2 * inset), CornerRadius(size.height / 2 - inset), blendMode = BlendMode.DstOver)
                    }
                    .horizontalScroll(scroll),
                contentAlignment = Alignment.CenterStart,
            ) {
                val last = (panes.size - 1).toFloat()
                Box(
                    Modifier.offset { IntOffset((position().coerceIn(0f, last) * segmentWidth().toPx()).roundToInt(), 0) }
                        .size(segmentWidth, segmentHeight).clip(Segment).background(chrome.text.copy(alpha = 0.15f).compositeOver(chrome.card)),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (pane in panes) {
                        val isCurrent = pane.block == current
                        Box(
                            Modifier.size(segmentWidth) { SwitcherHeight }.clip(Segment)
                                .clickable(role = Role.Tab) { onSelect(pane.block) }
                                .semantics { contentDescription = pane.title; selected = isCurrent },
                            contentAlignment = Alignment.Center,
                        ) { ProgramGlyph(pane.program, glyphSize, chrome.text) }
                    }
                }
            }
        }
        SplitButton(pull, armed, onSplit)
    }
}

/** A small plus inside a fixed slot, so its pull feedback never overlaps the key bar. */
@Composable
private fun SplitButton(pull: () -> Float, armed: Boolean, onSplit: () -> Unit) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(armed) {
        if (armed) {
            pop.snapTo(POP)
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
    }
    val card = chrome.card
    val accent = chrome.accent
    val text = chrome.text
    val background = chrome.background
    Box(
        Modifier.size(40.dp)
            .clickable(role = Role.Button, onClick = onSplit)
            .semantics { contentDescription = "Split Pane" },
        contentAlignment = Alignment.Center,
    ) {
      Box(Modifier
            .layout { measurable, _ ->
                val p = pull()
                val w = lerp(RestWidth, ArmedWidth, p).roundToPx()
                val h = lerp(RestHeight, ArmedHeight, p).roundToPx()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(HatchShapes.Capsule)
            .drawBehind {
                val p = pull()
                drawRoundRect(lerp(card, accent, p), cornerRadius = CornerRadius(size.height / 2))
                val tint = lerp(text, background, p)
                val w = lerp(1.6.dp, 1.7.dp, p).toPx()
                val half = 5.dp.toPx()
                val c = center
                drawLine(tint, Offset(c.x, c.y - half), Offset(c.x, c.y + half), w, StrokeCap.Round)
                drawLine(tint, Offset(c.x - half, c.y), Offset(c.x + half, c.y), w, StrokeCap.Round)
            },
      )
    }
}

// Size is read during measure, so animating it re-measures without recomposing.
private fun Modifier.size(width: () -> Dp, height: () -> Dp): Modifier = layout { measurable, constraints ->
    val w = constraints.constrainWidth(width().roundToPx())
    val h = constraints.constrainHeight(height().roundToPx())
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(w, h) { placeable.place(0, 0) }
}

internal val SwitcherRowHeight = 40.dp
private val SwitcherHeight = SwitcherRowHeight
private val Capsule = HatchShapes.Capsule
private val Segment = HatchShapes.Capsule
private val RestWidth = 28.dp
private val RestHeight = 20.dp
private val ArmedWidth = 34.dp
private val ArmedHeight = 28.dp
private const val POP = 1.15f
private val EdgeFade = 16.dp
