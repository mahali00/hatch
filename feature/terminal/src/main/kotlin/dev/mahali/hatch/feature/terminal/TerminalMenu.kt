package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenuCard
import dev.mahali.hatch.design.HatchMenuDivider
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HatchMenuPopup
import dev.mahali.hatch.design.chrome

internal class MenuActions(
    val find: () -> Unit,
    val splitPane: () -> Unit,
    val closePane: () -> Unit,
    /** Null when there is no earlier pane in this tab to return to. */
    val lastPane: (() -> Unit)?,
    val newTab: () -> Unit,
    val renameTab: () -> Unit,
    val moveTab: (forward: Boolean) -> Unit,
    val closeTab: () -> Unit,
    val renameSession: () -> Unit,
    val closeSession: () -> Unit,
    val commands: () -> Unit,
)

@Composable
internal fun TerminalMenu(
    expanded: Boolean,
    canMoveBack: Boolean,
    canMoveForward: Boolean,
    actions: MenuActions,
    layout: PaneView? = null,
    onLayout: (PaneView) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (!expanded) return
    var moving by remember { mutableStateOf(false) }
    fun act(action: () -> Unit) { onDismiss(); action() }
    HatchMenuPopup(onDismiss) {
        HatchMenuCard(Modifier.alpha(if (moving) 0.55f else 1f)) {
            HatchMenuItem("Find in Pane", { act(actions.find) }, HatchIcons.Search)
            HatchMenuItem("Split Pane", { act(actions.splitPane) }, HatchIcons.Split)
            HatchMenuItem("Close Pane", { act(actions.closePane) }, HatchIcons.Close)
            // Shown with the layout choices, when the tab has more than one pane.
            if (layout != null) HatchMenuItem("Last Pane", { actions.lastPane?.let(::act) }, HatchIcons.ChevronLeft, enabled = actions.lastPane != null)
            if (layout != null) {
                HatchMenuDivider()
                for (mode in PaneView.entries) HatchMenuItem(mode.label, { act { onLayout(mode) } }, mode.icon) {
                    if (mode == layout) Icon(HatchIcons.Check, null, Modifier.size(Dimens.Chevron), tint = chrome.accent)
                }
            }
            HatchMenuDivider()
            HatchMenuItem("New Tab", { act(actions.newTab) }, HatchIcons.Plus)
            HatchMenuItem("Rename Tab", { act(actions.renameTab) }, HatchIcons.Pencil)
            HatchMenuItem("Move Tab", { moving = true }, HatchIcons.Move) {
                Icon(HatchIcons.ChevronRight, null, Modifier.size(Dimens.Chevron), tint = chrome.secondary)
            }
            HatchMenuItem("Close Tab", { act(actions.closeTab) }, HatchIcons.Close)
            HatchMenuDivider()
            HatchMenuItem("Rename Session", { act(actions.renameSession) }, HatchIcons.Pencil)
            HatchMenuItem("Close Session", { act(actions.closeSession) }, HatchIcons.Trash, color = chrome.accent)
            HatchMenuDivider()
            HatchMenuItem("Commands", { act(actions.commands) }, HatchIcons.Search)
        }
        if (moving) {
            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Back") { moving = false })
            HatchMenuCard(Modifier.offset(y = SubmenuTop + if (layout != null) LayoutRows else 0.dp), width = 250.dp) {
                HatchMenuItem("Move Tab", { moving = false }, HatchIcons.ChevronLeft, color = chrome.secondary)
                HatchMenuItem("Move Backward", { act { actions.moveTab(false) } }, HatchIcons.ChevronLeft, enabled = canMoveBack)
                HatchMenuItem("Move Forward", { act { actions.moveTab(true) } }, HatchIcons.ChevronRight, enabled = canMoveForward)
            }
        }
    }
}

// Five rows, then a 13 dp divider (1 dp line, 6 dp above and below).
private val SubmenuTop: Dp @Composable get() = Dimens.MenuRowHeight * 5 + 13.dp

// The layout choices and Last Pane.
private val LayoutRows: Dp @Composable get() = Dimens.MenuRowHeight * (PaneView.entries.size + 1) + 13.dp

internal class Rename(val title: String, val current: String, val apply: (String) -> Unit)

internal class Confirm(val title: String, val message: String, val confirm: String, val apply: () -> Unit)
