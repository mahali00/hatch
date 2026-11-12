package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.CardSpacer
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.chrome
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.KeyBarKey
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.core.ShortcutGroup
import dev.mahali.hatch.design.Footnote
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchListCard
import dev.mahali.hatch.design.HatchListDivider
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchSheet
import dev.mahali.hatch.design.HatchSheetHeader
import dev.mahali.hatch.design.HatchSwitch
import java.util.UUID

@Composable
internal fun KeyBarLayoutPage(store: SettingsStore, back: (() -> Unit)?) {
    val settings by store.settings.collectAsState()
    SettingsPage("Key Bar Layout", back) {
        HatchListCard { ReorderableKeys(store, settings.keyBarKeys, settings.haptics) }
        Footnote("Drag a key by its handle to arrange the row. Cursor control and keyboard dismissal always stay available.")
        CardSpacer()
        HatchListCard { HatchListRow("Restore default layout", onClick = { store.update { it.copy(keyBarKeys = KeyBarKey.entries) } }) }
    }
}

@Composable
private fun ReorderableKeys(store: SettingsStore, visible: List<KeyBarKey>, haptics: Boolean) {
    var order by remember { mutableStateOf(visible) }
    var dragging by remember { mutableStateOf<KeyBarKey?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(visible) { if (dragging == null) order = visible }
    val view = LocalView.current
    fun feel(constant: Int) { if (haptics) view.performHapticFeedback(constant) }
    val save = { list: List<KeyBarKey> -> store.update { it.copy(keyBarKeys = list) } }
    val finish = {
        save(order)
        if (dragging != null) feel(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
        dragging = null
        dragY = 0f
    }
    val rows = order + KeyBarKey.entries.filter { it !in order }
    rows.forEachIndexed { index, key ->
        key(key) {
            if (index > 0) HatchListDivider()
            val shown = key in order
            val dragged = key == dragging
            val lift by animateFloatAsState(if (dragged) 1f else 0f, label = "lift")
            HatchListRow(
                key.label,
                Modifier.zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragged) dragY else 0f
                        scaleX = 1f + LIFT * lift; scaleY = scaleX
                        shadowElevation = 8.dp.toPx() * lift
                    }
                    .background(chrome.card).onSizeChanged { rowHeight = it.height.toFloat() }
                    .semantics {
                        val at = order.indexOf(key)
                        if (at >= 0) customActions = listOfNotNull(
                            CustomAccessibilityAction("Move earlier") { save(order.moved(at, at - 1)); true }.takeIf { at > 0 },
                            CustomAccessibilityAction("Move later") { save(order.moved(at, at + 1)); true }.takeIf { at < order.lastIndex },
                        )
                    },
                leading = {
                    // The glyph is 22; the handle takes a 48 touch target around it. Hidden keys have no place to move to.
                    Box(
                        Modifier.size(Dimens.TouchTarget).then(if (!shown) Modifier else Modifier.pointerInput(key) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = key; dragY = 0f; feel(HapticFeedbackConstants.LONG_PRESS) },
                                onDragEnd = finish,
                                onDragCancel = finish,
                            ) { change, dy ->
                                change.consume()
                                dragY += dy
                                val from = order.indexOf(key)
                                val to = (from + (dragY / rowHeight).roundToInt()).coerceIn(order.indices)
                                if (to != from) {
                                    order = order.moved(from, to)
                                    dragY -= (to - from) * rowHeight
                                    feel(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }),
                        contentAlignment = Alignment.Center,
                    ) { if (shown) Icon(HatchIcons.DragHandle, "Reorder ${key.label}", Modifier.size(Dimens.Icon), tint = chrome.secondary) }
                },
                trailing = {
                    HatchSwitch(shown, { on -> save(if (on) order + key else order - key) }, description = "Show ${key.label}")
                },
            )
        }
    }
}

private const val LIFT = 0.03f

@Composable
internal fun ShortcutGroupsPage(store: SettingsStore, back: (() -> Unit)?) {
    val settings by store.settings.collectAsState()
    var editing by remember { mutableStateOf<ShortcutGroup?>(null) }
    SettingsPage("Shortcut Groups", back) {
        HatchListCard {
            settings.shortcutGroups.forEachIndexed { index, group ->
                if (index > 0) HatchListDivider()
                HatchListRow(group.name, subtitle = group.shortcuts.joinToString("  "), onClick = { editing = group }, trailing = {
                    HeaderButton(HatchIcons.Trash, "Delete ${group.name}", { store.update { it.copy(shortcutGroups = it.shortcutGroups.filterNot { g -> g.id == group.id }) } })
                })
            }
            if (settings.shortcutGroups.size < 32) HatchListRow("Add shortcut group", onClick = { editing = ShortcutGroup(UUID.randomUUID().toString(), "", emptyList()) })
        }
        Footnote("Each group becomes a key-bar button. Tap it to choose one of its text shortcuts.")
    }
    editing?.let { group ->
        GroupEditor(group, onDismiss = { editing = null }, onSave = { saved ->
            store.update { s -> s.copy(shortcutGroups = if (s.shortcutGroups.any { it.id == saved.id }) s.shortcutGroups.map { if (it.id == saved.id) saved else it } else s.shortcutGroups + saved) }
            editing = null
        })
    }
}

@Composable
private fun GroupEditor(group: ShortcutGroup, onDismiss: () -> Unit, onSave: (ShortcutGroup) -> Unit) {
    var name by remember(group.id) { mutableStateOf(group.name) }
    var text by remember(group.id) { mutableStateOf(group.shortcuts.joinToString("\n")) }
    val shortcuts = text.lines().filter { it.isNotEmpty() }
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss = { keyboard?.hide(); onDismiss() }
    HatchSheet(dismiss) {
        HatchSheetHeader(if (group.name.isBlank()) "New shortcut group" else group.name, trailing = { HeaderButton(HatchIcons.Close, "Close", dismiss) })
        Column(Modifier.padding(Hatch.space.xl).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Hatch.space.m)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text("Shortcuts, one per line") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Button(onClick = { keyboard?.hide(); onSave(group.copy(name = name.trim(), shortcuts = shortcuts)) },
                enabled = name.isNotBlank() && shortcuts.size in 1..256 && shortcuts.all { it.length <= 1024 }) { Text("Save") }
        }
    }
}
