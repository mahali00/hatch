package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import dev.mahali.hatch.core.KeyBarKey
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.ShortcutGroup
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchSheet
import dev.mahali.hatch.design.HatchSheetHeader
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchListRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.renderer.CellMetrics
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome

// A null symbol means the key always shows its word, as escape does on every keyboard.
private class BarKey(val icon: ImageVector?, val word: String, val description: String, val mod: ModKey? = null, val name: String? = null)

// Labels follow a Mac, Windows or Linux keyboard; ⌘ is Super on the latter.
private fun keys(labels: Settings.KeyLabels): Map<KeyBarKey, BarKey> {
    val mac = labels == Settings.KeyLabels.Mac
    return mapOf(
        KeyBarKey.Escape to BarKey(null, "esc", "Escape", name = "escape"),
        KeyBarKey.Control to BarKey(KeyIcons.Control, if (mac) "ctrl" else "Ctrl", "Control", mod = ModKey.Ctrl),
        KeyBarKey.Option to BarKey(KeyIcons.Option, if (mac) "opt" else "Alt", if (mac) "Option" else "Alt", mod = ModKey.Alt),
        KeyBarKey.Command to BarKey(KeyIcons.Command, if (mac) "cmd" else "Super", if (mac) "Command" else "Super", mod = ModKey.Super),
        KeyBarKey.Shift to BarKey(KeyIcons.Shift, if (mac) "shift" else "Shift", "Shift", mod = ModKey.Shift),
        KeyBarKey.Tab to BarKey(KeyIcons.Tab, if (mac) "tab" else "Tab", "Tab", name = "tab"),
    )
}

@Composable
internal fun KeyBar(typing: Typing, settings: Settings, haptics: Haptics, mac: Boolean, onDismissKeyboard: () -> Unit, modifier: Modifier = Modifier) {
    // Windows and Linux keyboards print words, so their labels stay words whatever "Use symbols" says.
    val useSymbols = settings.useSymbols && settings.keyLabels == Settings.KeyLabels.Mac
    val keys = remember(settings.keyLabels) { keys(settings.keyLabels) }
    var group by remember { mutableStateOf<ShortcutGroup?>(null) }
    val armed = typing.armed.armed
    // The pad moves one cell per cell of travel, so it needs the terminal's cell size.
    val context = LocalContext.current
    val cell = remember(settings.fontFamily, settings.fontSize, settings.lineHeight) { CellMetrics.of(Fonts.terminal(context, settings)) }
    BoxWithConstraints(modifier.fillMaxWidth().alpha(if (typing.enabled) 1f else 0.4f)) {
        // The keys take the width they need and the row sits in the middle. On a narrow
        // screen the middle group stops at the room left beside the side buttons and scrolls.
        val middleMax = (maxWidth - PadWidth - KeyGap - Hatch.space.l).coerceAtLeast(0.dp)
        Row(
            Modifier.fillMaxWidth().padding(start = Hatch.space.s, end = Hatch.space.s, bottom = Dimens.ImeBarGap - CapsuleInset),
            horizontalArrangement = Arrangement.spacedBy(KeyGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Capsule(Modifier.widthIn(max = middleMax)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Hatch.space.xs),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (id in settings.keyBarKeys.withCommand(mac)) {
                        val key = keys.getValue(id)
                        val mod = key.mod
                        ModKeyButton(key.icon, key.word, key.description, useSymbols, mod != null && mod in armed) {
                            if (mod != null) typing.toggle(mod) else key.name?.let(typing::named)
                        }
                    }
                    settings.shortcutGroups.forEach { item ->
                        Box(Modifier.fillMaxHeight().clickable(role = Role.Button) { group = item }.padding(horizontal = Hatch.space.s), contentAlignment = Alignment.Center) {
                            Text(item.name, style = HatchType.Callout, color = chrome.text, maxLines = 1)
                        }
                    }
                }
            }
            Capsule(Modifier.width(PadWidth)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    CursorPad(cell.width, cell.height, onArrow = { typing.named(it.key) }, onTouch = haptics::key)
                    Box(
                        Modifier.width(KeyWidth).fillMaxHeight().clickable(role = Role.Button, onClick = onDismissKeyboard)
                            .semantics { contentDescription = "Hide keyboard" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(KeyIcons.HideKeyboard, null, Modifier.size(Hatch.size.keyBarIcon), tint = chrome.text) }
                }
            }
        }
    }
    group?.let { selected ->
        HatchSheet({ group = null }) {
            HatchSheetHeader(selected.name, trailing = { HeaderButton(HatchIcons.Close, "Close", { group = null }) })
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Hatch.space.xxl)) {
                selected.shortcuts.forEach { text -> HatchListRow(text, onClick = { typing.shortcut(text); group = null }) }
            }
        }
    }
}

// Mac rows always include Command, even in layouts saved without it.
private fun List<KeyBarKey>.withCommand(mac: Boolean): List<KeyBarKey> {
    if (!mac || KeyBarKey.Command in this) return this
    val at = indexOf(KeyBarKey.Shift).let { if (it >= 0) it else size }
    return toMutableList().apply { add(at, KeyBarKey.Command) }
}

private val CapsuleInset = 4.dp

private val KeyWidth = 40.dp
private val PadWidth = 80.dp
private val KeyGap = 6.dp

internal val KeyBarHeight = 52.dp

@Composable
private fun Capsule(modifier: Modifier, content: @Composable () -> Unit) {
    // The pill is inset in the touch row. Clip to it so a pressed key cannot draw outside.
    Box(modifier.height(Dimens.ImeBarHeight + CapsuleInset * 2).padding(vertical = CapsuleInset).clip(HatchShapes.Capsule), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(chrome.card))
        content()
    }
}

@Composable
private fun ModKeyButton(icon: ImageVector?, word: String, description: String, useSymbols: Boolean, armed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().then(if (useSymbols && icon != null) Modifier.width(KeyWidth) else Modifier.widthIn(min = KeyWidth)).clip(HatchShapes.Capsule)
            .background(if (armed) chrome.text.copy(alpha = 0.15f).compositeOver(chrome.card) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; selected = armed }
            .padding(horizontal = if (useSymbols && icon != null) Hatch.space.xs else Hatch.space.s),
        contentAlignment = Alignment.Center,
    ) {
        if (useSymbols && icon != null) Icon(icon, null, Modifier.size(Hatch.size.keyBarIcon), tint = chrome.text)
        else Text(word, style = HatchType.Callout, color = chrome.text, maxLines = 1)
    }
}
