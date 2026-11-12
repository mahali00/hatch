package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchMenuShadow
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.floatingEdge
import dev.mahali.hatch.engine.Pane
import dev.mahali.hatch.ghostty.ScreenCell
import dev.mahali.hatch.renderer.TerminalView
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Stable
internal class Selection {
    var range: Pair<ScreenCell, ScreenCell>? by mutableStateOf(null)

    // Select All copies the server's formatted text, which reaches past local history.
    var all: Boolean by mutableStateOf(false)

    fun clear() {
        range = null
        all = false
    }
}

private val HandleTouch = 48.dp
private val HandleDot = 14.dp
private val StartHandle = RoundedCornerShape(topStart = 7.dp, topEnd = 0.dp, bottomEnd = 7.dp, bottomStart = 7.dp)
private val EndHandle = RoundedCornerShape(topStart = 0.dp, topEnd = 7.dp, bottomEnd = 7.dp, bottomStart = 7.dp)

@Composable
internal fun BoxScope.SelectionLayer(
    selection: Selection,
    pane: Pane,
    view: TerminalView,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val range = selection.range ?: return
    // Followed per frame only while a selection shows, so only a value change recomposes.
    val top by produceState(pane.geometry.scroll.offset, pane) { while (true) withFrameNanos { value = pane.geometry.scroll.offset } }
    var dragging by remember { mutableIntStateOf(NONE) }
    var edge by remember { mutableIntStateOf(0) }
    val (a, b) = if (range.first <= range.second) range else range.second to range.first
    val density = LocalDensity.current
    val touch = with(density) { HandleTouch.toPx() }

    fun moveHandle(which: Int, finger: Offset) {
        val rows = pane.geometry.scroll.rows.coerceAtLeast(1)
        val row = view.rowAt(finger.y)
        edge = when { row < 0 -> -1; row >= rows -> 1; else -> 0 }
        val cell = ScreenCell(view.columnAt(finger.x), pane.geometry.scroll.offset + row.coerceIn(0, rows - 1))
        val current = selection.range ?: return
        selection.all = false
        selection.range = if (which == START) cell to current.second else current.first to cell
    }

    LaunchedEffect(edge, dragging) {
        while (edge != 0 && dragging != NONE) {
            pane.scroll(edge)
            delay(EDGE_SCROLL_MILLIS)
            val rows = pane.geometry.scroll.rows.coerceAtLeast(1)
            val row = if (edge < 0) 0 else rows - 1
            val current = selection.range ?: break
            val moved = if (dragging == START) current.first else current.second
            val cell = ScreenCell(moved.x, pane.geometry.scroll.offset + row)
            selection.range = if (dragging == START) cell to current.second else current.first to cell
        }
    }

    val startAt = Offset(view.cellLeft(a.x), view.cellTop(a.y - top))
    val endAt = Offset(view.cellLeft(b.x + 1), view.cellTop(b.y - top + 1))
    for ((which, at) in listOf(START to startAt, END to endAt)) {
        Box(
            Modifier.offset { IntOffset((at.x - touch / 2).roundToInt(), (at.y - touch / 2).roundToInt()) }
                .size(HandleTouch)
                .semantics { contentDescription = if (which == START) "Selection start" else "Selection end" }
                .pointerInput(which) {
                    var finger = at
                    detectDragGestures(
                        onDragStart = { finger = at; dragging = which },
                        onDragEnd = { dragging = NONE; edge = 0 },
                        onDragCancel = { dragging = NONE; edge = 0 },
                    ) { change, amount ->
                        change.consume()
                        finger += amount
                        moveHandle(which, finger)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.offset(if (which == START) -HandleDot / 2 else HandleDot / 2, HandleDot / 2).size(HandleDot).clip(if (which == START) StartHandle else EndHandle).background(chrome.accent))
        }
    }

    if (dragging == NONE) {
        val menuHeight = with(density) { MenuHeight.toPx() }
        val above = startAt.y - menuHeight - touch / 2
        val y = if (above >= 0) above else endAt.y + touch / 2
        SelectionMenu(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, y.roundToInt()) }, onCopy, onPaste, onSelectAll)
    }
}

private val MenuHeight: Dp @Composable get() = Dimens.MenuRowHeight

@Composable
private fun SelectionMenu(modifier: Modifier, onCopy: () -> Unit, onPaste: () -> Unit, onSelectAll: () -> Unit) {
    val shape = HatchShapes.Capsule
    Row(
        // No wrap: on a narrow tile the menu can be wider than the pane, as on iOS.
        modifier.wrapContentWidth(unbounded = true).height(MenuHeight).dropShadow(shape, HatchMenuShadow).clip(shape).background(chrome.card).floatingEdge(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuAction("Copy", onCopy)
        MenuDivider()
        MenuAction("Paste", onPaste)
        MenuDivider()
        MenuAction("Select All", onSelectAll)
    }
}

@Composable
private fun MenuAction(text: String, onClick: () -> Unit) {
    Box(Modifier.height(MenuHeight).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Dimens.IconGap), contentAlignment = Alignment.Center) {
        Text(text, style = HatchType.Body, color = chrome.text, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun MenuDivider() = Box(Modifier.width(1.dp).height(20.dp).background(chrome.divider))

private const val NONE = 0
private const val START = 1
private const val END = 2
private const val EDGE_SCROLL_MILLIS = 60L
