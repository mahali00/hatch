package dev.mahali.hatch.design

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.annotation.DrawableRes
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
public fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = Dimens.SectionHeaderStart, end = Dimens.SectionHeaderStart, top = 4.dp, bottom = 16.dp),
        style = HatchType.LargeTitle, color = chrome.text,
    )
}

@Composable
public fun SectionHeader(text: String, modifier: Modifier = Modifier, top: Dp = Dimens.SectionGap) {
    Text(
        text, modifier.padding(start = Dimens.SectionHeaderStart, end = Dimens.SectionHeaderStart, top = top, bottom = Dimens.HeaderGap),
        style = Hatch.type.section, color = chrome.secondary,
    )
}

@Composable
public fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = Dimens.SectionHeaderStart, end = Dimens.SectionHeaderStart, top = Dimens.HeaderGap),
        style = HatchType.Footnote, color = chrome.secondary,
    )
}

@Composable
public fun HatchListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = LocalListMetrics.current.inset).fillMaxWidth().clip(HatchShapes.Card).background(chrome.card),
        content = content,
    )
}

@Composable
public fun HatchListDivider(start: Dp = LocalListMetrics.current.let { it.padding + it.leading + it.gap }) {
    Box(Modifier.padding(start = start, end = LocalListMetrics.current.padding).fillMaxWidth().height(1.dp).background(chrome.divider))
}

@Composable
public fun HatchListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** A long press, with [onLongClickLabel] for TalkBack; it needs [onClick]. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val metrics = LocalListMetrics.current
    // A full-row ripple would draw square corners, so the rounded shape is set here; the whole row still takes the tap.
    val shape = RoundedCornerShape(metrics.selectedRadius)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.combinedClickable(interaction, indication = null, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick) else Modifier)
            .heightIn(min = metrics.rowHeight)
            .padding(horizontal = SelectedInset, vertical = metrics.selectedInset)
            .clip(shape)
            .then(if (selected) Modifier.background(chrome.control) else Modifier)
            .then(if (selected && metrics.quiet) Modifier.border(1.dp, chrome.text.copy(alpha = 0.08f), shape) else Modifier)
            .then(if (onClick != null) Modifier.indication(interaction, LocalIndication.current) else Modifier)
            .padding(horizontal = metrics.padding - SelectedInset, vertical = maxOf(0.dp, 9.dp - metrics.selectedInset)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
    ) {
        if (leading != null) Box(Modifier.widthIn(min = metrics.leading), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), content = content)
        trailing?.invoke(this)
    }
}

private val SelectedInset = 6.dp

@Composable
public fun HatchListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = chrome.text,
    subtitleColor: Color = chrome.secondary,
    titleWeight: FontWeight? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    HatchListRow(modifier, onClick, selected, leading, trailing) {
        Text(title, style = LocalListMetrics.current.body, color = titleColor, fontWeight = titleWeight, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, style = HatchType.Callout, color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
public fun RowChevron(tint: Color = chrome.secondary) {
    Icon(HatchIcons.ChevronRight, null, Modifier.size(LocalListMetrics.current.chevron), tint = tint)
}

@Composable
public fun IconBadge(icon: SettingsIcon, modifier: Modifier = Modifier) {
    Box(modifier.size(Hatch.size.leading), contentAlignment = Alignment.Center) {
        // Rex's art carries a shadow round its tile; drawn larger by that margin, the tile is [leading].
        if (icon == SettingsIcon.About) HatchIcon(Modifier.size(Hatch.size.leading))
        else Image(painterResource(if (chrome.dark) icon.dark else icon.light), null, Modifier.requiredSize(Hatch.size.leading / SettingsArtTile))
    }
}

/** Rex's settings art is 68 px with a 56 px tile. */
private const val SettingsArtTile = 56f / 68f

/** The accent is paled on dark themes: the full accent glared there. */
@Composable
public fun HatchSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    val on = if (chrome.dark) lerp(chrome.accent, Color.White, 0.45f) else chrome.accent
    // A timed ease, not a spring: One UI's switch slides, and a spring read as a snap.
    val motion = Hatch.motion
    val track by animateColorAsState(if (checked) on else chrome.control, motion.spec(motion.standard), label = "track")
    val w = Hatch.size.switchWidth
    val h = Hatch.size.switchHeight
    val knob = h - SwitchInset * 2
    val thumb by animateDpAsState(if (checked) w - h + SwitchInset else SwitchInset, motion.spec(motion.standard), label = "thumb")
    Box(
        modifier.size(w, h).clip(HatchShapes.Capsule).background(track)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { if (description != null) contentDescription = description },
    ) {
        Box(Modifier.offset { IntOffset(thumb.roundToPx(), SwitchInset.roundToPx()) }.size(knob).clip(CircleShape).background(Color.White))
    }
}

private val SwitchInset = 3.dp

public enum class ButtonSize {
    Standard,
    Toolbar,
    Row,
    Bar,
}

/** With [selected] it is a toggle and reads as a switch to TalkBack. */
@Composable
public fun HeaderButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = chrome.text,
    size: ButtonSize = ButtonSize.Standard,
    selected: Boolean? = null,
) {
    val list = LocalListMetrics.current
    val target = when (size) {
        ButtonSize.Standard -> Hatch.size.touchTarget
        ButtonSize.Toolbar -> Hatch.size.toolbarButton
        ButtonSize.Row -> list.headerHeight
        ButtonSize.Bar -> Hatch.size.floatingButton
    }
    val glyph = when (size) {
        ButtonSize.Standard, ButtonSize.Bar -> Hatch.size.icon
        ButtonSize.Toolbar -> Hatch.size.toolbarIcon
        ButtonSize.Row -> list.actionIcon
    }
    val press = if (selected == null) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
    Box(
        modifier.size(target).clip(CircleShape).then(press).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(glyph), tint = tint) }
}

@Composable
public fun FloatingButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(Dimens.FloatingButton).clip(CircleShape).background(floatingBarFill())
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(Dimens.Icon), tint = chrome.text) }
}

@Composable
public fun HatchButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = chrome.accent,
    content: Color = chrome.onAccent,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier.height(Dimens.ControlHeight).alpha(if (enabled) 1f else 0.4f).clip(HatchShapes.Capsule).background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(Dimens.Icon), tint = content)
        Text(label, style = HatchType.Body, color = content, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
public fun HatchPill(
    modifier: Modifier = Modifier,
    end: Dp = Hatch.layout.cardPadding,
    gap: Dp = Hatch.layout.iconGap,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.clip(HatchShapes.Capsule).background(chrome.card).floatingEdge(HatchShapes.Capsule)
            .padding(start = Hatch.layout.cardPadding, end = end, top = Hatch.space.s, bottom = Hatch.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
public fun HatchPill(text: String, modifier: Modifier = Modifier) {
    HatchPill(modifier) { HatchPillText(text, chrome.text) }
}

@Composable
public fun RowScope.HatchPillText(text: String, color: Color = chrome.secondary) {
    Text(
        text, Modifier.weight(1f, fill = false), style = HatchType.Callout, color = color,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
public fun HatchPillButton(text: String, onClick: () -> Unit, color: Color = chrome.accent, background: Color = chrome.control) {
    Box(
        Modifier.clip(HatchShapes.Capsule).background(background).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = HatchType.Subhead, color = color, maxLines = 1, softWrap = false) }
}

@Composable
public fun floatingFill(): Color = chrome.text.copy(alpha = 0.06f).compositeOver(chrome.card)

@Composable
public fun floatingBarFill(): Color = floatingFill().copy(alpha = 0.86f)

/** Not for the bottom bar's controls, which use [floatingFill] alone. */
@Composable
public fun Modifier.floatingEdge(shape: Shape): Modifier = border(1.dp, chrome.text.copy(alpha = 0.08f), shape)

@Composable
public fun HatchFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = Hatch.layout.fieldPadding, top = Hatch.space.xs), style = Hatch.type.label, color = chrome.secondary)
}

@Composable
public fun SelectedCheck(modifier: Modifier = Modifier) {
    Icon(HatchIcons.Check, null, modifier.size(Hatch.size.icon), tint = chrome.accent)
}

@Composable
public fun SelectedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(Hatch.size.icon + Hatch.space.xs).clip(CircleShape).background(chrome.accent), contentAlignment = Alignment.Center) {
        Icon(HatchIcons.Check, null, Modifier.size(Hatch.size.iconSmall), tint = chrome.onAccent)
    }
}

@Composable
public fun HatchChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, clickLabel: String? = null) {
    Text(
        label, style = Hatch.type.label, color = chrome.text,
        modifier = modifier.heightIn(min = Hatch.size.touchTarget).wrapContentHeight().clip(HatchShapes.Capsule).background(chrome.control)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .padding(horizontal = Hatch.space.m, vertical = Hatch.space.xs + Hatch.space.xxs),
    )
}

/** Sits where the sidebar toggle sits, so the toggle never moves between pages. */
@Composable
public fun HatchTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    backIcon: ImageVector = HatchIcons.ChevronLeft,
    backDescription: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = ListMetrics.Sidebar.inset, end = Hatch.layout.sidebarToggleStart, top = Hatch.layout.sidebarToggleTop)
            .height(Hatch.size.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) HeaderButton(backIcon, backDescription, onBack, size = ButtonSize.Toolbar)
        Text(
            title.orEmpty(), Modifier.weight(1f).padding(start = if (onBack != null) Hatch.space.xs else Hatch.layout.screenInset + Hatch.space.s),
            style = Hatch.type.title, color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** Settings art comes from Rex; About is Hatch's own icon. */
public enum class SettingsIcon(@DrawableRes internal val light: Int, @DrawableRes internal val dark: Int = light) {
    Hosts(R.drawable.settings_icon_hosts),
    Interface(R.drawable.settings_icon_interface_light, R.drawable.settings_icon_interface_dark),
    Themes(R.drawable.settings_icon_themes),
    Terminal(R.drawable.settings_icon_terminal),
    Keyboard(R.drawable.settings_icon_keyboard_light, R.drawable.settings_icon_keyboard_dark),
    Notifications(R.drawable.settings_icon_notifications),
    Developer(R.drawable.settings_icon_developer),
    About(R.drawable.hatch_icon),
}
