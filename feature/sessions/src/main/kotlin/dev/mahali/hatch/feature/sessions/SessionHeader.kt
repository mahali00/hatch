package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.design.ListMetrics
import dev.mahali.hatch.design.LocalListMetrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchConfirmDialog
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenu
import dev.mahali.hatch.design.HatchMenuDivider
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome

@Composable
internal fun SessionHeader(
    label: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onNewTab: () -> Unit,
    onRename: () -> Unit,
    onCollapseOthers: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onMenuShown: (Boolean) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val show = { open: Boolean -> menu = open; onMenuShown(open) }
    Box(modifier) {
        val quiet = LocalListMetrics.current.quiet
        GroupRow(label, collapsed, onToggle, onLongClick = { show(true) }) {
            if (!quiet) GroupAction(HatchIcons.AddCircle, "New tab in $label", onNewTab)
        }
        HatchMenu(menu, onDismiss = { show(false) }, alignEnd = false) {
            if (quiet) HatchMenuItem("New Tab", { show(false); onNewTab() }, HatchIcons.AddCircle)
            HatchMenuItem("Rename", { show(false); onRename() }, HatchIcons.Pencil)
            HatchMenuItem("Collapse Other Sessions", { show(false); onCollapseOthers() }, HatchIcons.CollapseOthers)
            HatchMenuDivider()
            HatchMenuItem("Close Session", { show(false); confirmClose = true }, HatchIcons.Trash, color = chrome.accent)
        }
        if (confirmClose) HatchConfirmDialog(
            "Close Session?", "Every tab in this session closes, and the programs running in them end.", "Close Session",
            onConfirm = onClose, onDismiss = { confirmClose = false },
        )
    }
}

@Composable
internal fun GroupRow(
    label: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val metrics = LocalListMetrics.current
    val end = groupEndPadding()
    val labelPadding = if (metrics == ListMetrics.Standard) Dimens.SectionHeaderStart - metrics.inset else metrics.padding
    val glyphStart = metrics.headerHeight - metrics.inset - metrics.padding - metrics.chevron + end
    Row(
        modifier.padding(start = metrics.inset, end = end).heightIn(min = metrics.headerHeight)
            .combinedClickable(
                onClickLabel = if (collapsed) "Expand" else "Collapse",
                onLongClickLabel = if (onLongClick != null) "Session options" else null,
                onLongClick = onLongClick,
                onClick = onToggle,
            )
            .padding(start = labelPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionName(label, Modifier.weight(1f))
        actions()
        Box(Modifier.size(metrics.headerHeight), contentAlignment = Alignment.CenterStart) {
            if (!metrics.quiet || collapsed) Icon(
                if (collapsed) HatchIcons.ChevronRight else HatchIcons.ChevronDown,
                contentDescription = null, modifier = Modifier.padding(start = glyphStart).size(metrics.chevron),
                tint = chrome.secondary,
            )
        }
    }
}

@Composable
private fun GroupAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val metrics = LocalListMetrics.current
    val chevronFromRight = metrics.inset + metrics.padding + metrics.chevron / 2
    val glyphEnd = chevronFromRight + GroupPitch - groupEndPadding() - metrics.headerHeight - metrics.actionIcon / 2
    Box(
        Modifier.size(metrics.headerHeight).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.CenterEnd,
    ) { Icon(icon, null, Modifier.padding(end = glyphEnd).size(metrics.actionIcon), tint = chrome.secondary) }
}

@Composable
internal fun SessionName(label: String, modifier: Modifier = Modifier) {
    val metrics = LocalListMetrics.current
    Text(label, modifier, style = metrics.label, color = if (metrics.quiet) chrome.tertiary else chrome.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// ⊕ to the chevron, centre to centre (iOS IMG_2890: 30.2 pt).
private val GroupPitch = 30.dp


// The chevron's glyph lines up with the tab rows' chevrons below, centred in a [target]-wide button.
@Composable
internal fun chevronEnd(target: Dp = Dimens.TouchTarget) = LocalListMetrics.current.let { it.inset + it.padding + it.chevron / 2 } - target / 2

@Composable
private fun groupEndPadding() = LocalListMetrics.current.headerHeight.let { maxOf(0.dp, chevronEnd(it) - (it - GroupPitch) / 2) }
