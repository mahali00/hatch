package dev.mahali.hatch.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.animation.expandIn
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

public val HatchMenuShadow: Shadow = Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.10f), offset = DpOffset(0.dp, 2.dp))

@Composable
public fun HatchMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = true,
    below: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    HatchMenuPopup(onDismiss, alignEnd, below) { HatchMenuCard(modifier, content = content) }
}

@Composable
public fun HatchMenuPopup(onDismiss: () -> Unit, alignEnd: Boolean = true, below: Dp = 4.dp, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    val room = with(density) { ShadowRoom.roundToPx() }
    val gap = with(density) { below.roundToPx() }
    val margin = with(density) { MenuMargin.roundToPx() }
    val top = WindowInsets.safeDrawing.getTop(density) + margin
    val bottom = LocalWindowInfo.current.containerSize.height - WindowInsets.safeDrawing.getBottom(density) - margin
    var cardMax by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val position = remember(alignEnd, room, gap, top, bottom) { MenuPosition(alignEnd, room, gap, top, bottom) { cardMax = it } }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val motion = Hatch.motion
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val max = if (cardMax == Int.MAX_VALUE) Dp.Infinity else with(density) { cardMax.toDp() }
        CompositionLocalProvider(LocalMenuMaxHeight provides max) {
            Box(Modifier.padding(ShadowRoom)) {
                AnimatedVisibility(
                    shown,
                    enter = expandIn(
                        motion.spec(motion.standard),
                        expandFrom = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
                        initialSize = { IntSize((it.width * 0.4f).toInt(), (it.height * 0.2f).toInt()) },
                    ) + fadeIn(motion.spec((motion.standard - MENU_FADE_DELAY).coerceAtLeast(0), delayMillis = if (motion.standard == 0) 0 else MENU_FADE_DELAY)),
                ) { Box(content = content) }
            }
        }
    }
}

@Composable
public fun HatchMenuCard(modifier: Modifier = Modifier, width: Dp = Dimens.MenuWidth, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.width(width).heightIn(max = LocalMenuMaxHeight.current)
            .then(if (chrome.dark) Modifier else Modifier.dropShadow(HatchShapes.Card, HatchMenuShadow))
            .clip(HatchShapes.Card).background(if (chrome.dark) floatingFill() else chrome.card)
            .then(if (chrome.dark) Modifier else Modifier.floatingEdge(HatchShapes.Card))
            .verticalScroll(rememberScrollState()).padding(vertical = Dimens.MenuPaddingV),
        content = content,
    )
}

@Composable
public fun HatchMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    color: Color = chrome.text,
    enabled: Boolean = true,
    tintIcon: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.MenuRowHeight).alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Dimens.MenuPaddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.MenuIconGap),
    ) {
        when {
            leading != null -> Box(Modifier.size(Dimens.Icon), contentAlignment = Alignment.Center) { leading() }
            icon != null -> Icon(icon, null, Modifier.size(Dimens.Icon), tint = if (tintIcon) color else Color.Unspecified)
        }
        Text(text, Modifier.weight(1f), style = HatchType.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
public fun HatchMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.MenuPaddingH, vertical = 6.dp).height(1.dp).background(chrome.divider))
}

private class MenuPosition(
    private val alignEnd: Boolean,
    private val room: Int,
    private val gap: Int,
    private val top: Int,
    private val bottom: Int,
    private val onCardMax: (Int) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (alignEnd) anchorBounds.right - popupContentSize.width + room else anchorBounds.left - room
        val card = popupContentSize.height - 2 * room
        val spaceBelow = bottom - (anchorBounds.bottom + gap)
        val spaceAbove = anchorBounds.top - gap - top
        onCardMax(maxOf(spaceBelow, spaceAbove, 0))
        val fromTop = bottom - anchorBounds.top
        val cardTop = when {
            card <= fromTop -> anchorBounds.top
            card <= anchorBounds.bottom - top -> anchorBounds.bottom - card
            spaceBelow >= spaceAbove -> anchorBounds.bottom + gap
            else -> anchorBounds.top - gap - card
        }
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            cardTop.coerceIn(top, (bottom - card).coerceAtLeast(top)) - room,
        )
    }
}

private val LocalMenuMaxHeight = staticCompositionLocalOf { Dp.Infinity }

// Room around the menu inside its popup window, so the shadow is not clipped.
private val ShadowRoom = 16.dp

private val MenuMargin = 8.dp
private const val MENU_FADE_DELAY = 60
