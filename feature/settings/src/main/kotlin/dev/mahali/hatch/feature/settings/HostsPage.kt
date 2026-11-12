package dev.mahali.hatch.feature.settings

import androidx.compose.runtime.collectAsState
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.CardSpacer
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.design.HatchListCard
import dev.mahali.hatch.design.HatchListDivider
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.SectionHeader
import dev.mahali.hatch.design.chrome
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun HostsPage(env: PageEnv, back: (() -> Unit)?) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val hosts = env.savedHosts
    SettingsPage(
        "Remote Hosts", back,
        action = {
            when {
                editing -> DoneButton { editing = false }
                hosts.isNotEmpty() -> Text(
                    "Edit",
                    Modifier.clip(HatchShapes.Capsule).clickable(role = Role.Button) { editing = true }
                        .heightIn(min = Dimens.TouchTarget).padding(horizontal = Hatch.space.m, vertical = 13.dp),
                    style = HatchType.Title, color = chrome.text,
                )
            }
        },
    ) {
        if (hosts.isNotEmpty()) {
            SectionHeader("Hosts", top = 0.dp)
            HatchListCard { if (editing) ReorderableHosts(env) else SwipeableHosts(env) }
            CardSpacer()
        }
        HatchListCard {
            val (accent, card) = chrome.accent to chrome.card
            val icon = remember(accent, card) { HatchIcons.addHost(accent, card) }
            HatchListRow(
                "Add Host", titleColor = chrome.accent, onClick = env.onAddHost,
                leading = { Icon(icon, null, tint = Color.Unspecified) },
            )
        }
        SectionHeader("Client Info")
        HatchListCard { LabelRow(env, env.settings.settings.collectAsState().value.clientLabel) }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) = HeaderButton(HatchIcons.Check, "Done", onClick, tint = chrome.accent)

@Composable
private fun SwipeableHosts(env: PageEnv) {
    env.savedHosts.forEachIndexed { i, host ->
        key(host.id) {
            if (i > 0) HatchListDivider()
            SwipeHostRow(
                host, env,
                onDelete = { env.hosts.remove(host.id) },
                onReconnect = { env.actions.reconnect(host.id) },
            )
        }
    }
}

@Composable
private fun SwipeHostRow(host: Host, env: PageEnv, onDelete: () -> Unit, onReconnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val reveal = with(LocalDensity.current) { 118.dp.toPx() }
    val close = { scope.launch { offset.animateTo(0f) } }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.align(Alignment.CenterEnd).padding(end = Hatch.space.m), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SwipeAction(HatchIcons.Reconnect, "Reconnect", chrome.accent, chrome.onAccent) { close(); onReconnect() }
            SwipeAction(HatchIcons.Trash, "Delete", chrome.danger, chrome.onDanger, onDelete)
        }
        HostRow(
            host, env.state.byHost[host.id]?.link,
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.background(chrome.card)
                .draggable(
                    rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(-reveal, 0f)) } },
                    Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val open = velocity < -800f || (velocity <= 800f && offset.value < -reveal / 2)
                        offset.animateTo(if (open) -reveal else 0f)
                    },
                )
                .clickable { if (offset.value != 0f) close() else env.onEditHost(host.id) }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Reconnect") { onReconnect(); true },
                        CustomAccessibilityAction("Delete") { onDelete(); true },
                    )
                },
        ) {
            HeaderButton(HatchIcons.Info, "Edit ${host.name}", { env.onEditHost(host.id) }, tint = chrome.secondary)
        }
    }
}

@Composable
private fun SwipeAction(icon: ImageVector, label: String, color: Color, content: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(Dimens.TouchTarget).clip(CircleShape).background(color).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(Dimens.Icon), tint = content) }
}

@Composable
private fun ReorderableHosts(env: PageEnv) {
    var order by remember { mutableStateOf(env.savedHosts) }
    var dragging by remember { mutableStateOf<HostId?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(env.savedHosts) { if (dragging == null) order = env.savedHosts }
    val save = { list: List<Host> -> env.hosts.reorder(list.map { it.id }) }
    val finish = {
        save(order)
        dragging = null
        dragY = 0f
    }

    order.forEachIndexed { index, host ->
        key(host.id) {
            if (index > 0) HatchListDivider()
            val dragged = host.id == dragging
            HostRow(
                host, env.state.byHost[host.id]?.link,
                Modifier.zIndex(if (dragged) 1f else 0f).graphicsLayer { translationY = if (dragged) dragY else 0f }
                    .background(chrome.card).onSizeChanged { rowHeight = it.height.toFloat() }
                    .semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction("Move up") { save(order.moved(index, index - 1)); true }.takeIf { index > 0 },
                            CustomAccessibilityAction("Move down") { save(order.moved(index, index + 1)); true }.takeIf { index < order.lastIndex },
                        )
                    },
                handle = {
                    Box(
                        Modifier.size(Dimens.TouchTarget).pointerInput(host.id) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = host.id; dragY = 0f },
                                onDragEnd = finish,
                                onDragCancel = finish,
                            ) { change, dy ->
                                change.consume()
                                dragY += dy
                                val from = order.indexOfFirst { it.id == host.id }
                                val to = (from + (dragY / rowHeight).roundToInt()).coerceIn(order.indices)
                                if (to != from) {
                                    order = order.moved(from, to)
                                    dragY -= (to - from) * rowHeight
                                }
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(HatchIcons.DragHandle, "Reorder ${host.name}", Modifier.size(Dimens.Icon), tint = chrome.secondary) }
                },
            ) {
                HeaderButton(HatchIcons.Remove, "Delete ${host.name}", { env.hosts.remove(host.id) }, tint = chrome.danger)
            }
        }
    }
}
