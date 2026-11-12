package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.ProgramGlyph
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenuCard
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HatchMenuPopup
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome

internal enum class PaneAction { SplitRight, SplitBelow, Zoom, Move, Narrower, Wider, Close }

@Composable
internal fun PaneTitleBar(
    strip: TileStrip,
    focused: Boolean,
    zoomed: Boolean,
    onAction: (PaneAction) -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    // Rex Mac fade: unfocused surface at 80% (98% on light themes), title at 70% over it.
    val surface = if (focused) chrome.surface else lerp(Color.Black, chrome.surface, if (chrome.dark) SURFACE_FADE else LIGHT_SURFACE_FADE)
    val tint = if (focused) chrome.text else chrome.text.copy(alpha = CONTENT_FADE).compositeOver(surface)
    val divider = chrome.divider
    Row(modifier.fillMaxWidth().height(StripHeight).background(surface).drawBehind {
        drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(titleModifier.weight(1f).fillMaxHeight().padding(horizontal = Hatch.space.s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (focused) Box(Modifier.size(7.dp, 14.dp).drawBehind {
                for (x in listOf(1.5f, 5.5f)) for (y in listOf(3f, 7f, 11f))
                    drawCircle(tint, 0.8.dp.toPx(), Offset(x.dp.toPx(), y.dp.toPx()))
            })
            ProgramGlyph(strip.program, 14.dp, tint)
            Text(strip.title.primary, style = HatchType.Footnote, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Touch has no hover, so only the focused pane shows ⋯; holding any strip opens the same menu.
        Box(Modifier.padding(end = Hatch.space.xs)) {
            if (focused) Box(
                Modifier.size(StripHeight).clip(CircleShape).clickable(role = Role.Button) { onMenu(true) }.semantics { contentDescription = "Pane actions" },
                contentAlignment = Alignment.Center,
            ) { Icon(HatchIcons.MoreHorizontal, null, Modifier.size(Hatch.size.chevron), tint = tint) }
            if (menuOpen) PaneActionMenu(zoomed, scrollable, { onMenu(false); onAction(it) }) { onMenu(false) }
        }
    }
}

@Composable
private fun PaneActionMenu(zoomed: Boolean, scrollable: Boolean, action: (PaneAction) -> Unit, onDismiss: () -> Unit) {
    HatchMenuPopup(onDismiss) {
        HatchMenuCard {
            HatchMenuItem("Split right", { action(PaneAction.SplitRight) }, HatchIcons.Split)
            HatchMenuItem("Split below", { action(PaneAction.SplitBelow) }, HatchIcons.SplitBelow)
            if (scrollable) {
                HatchMenuItem("Widen column", { action(PaneAction.Wider) }, HatchIcons.Split)
                HatchMenuItem("Narrow column", { action(PaneAction.Narrower) }, HatchIcons.Split)
            }
            HatchMenuItem("Move pane", { action(PaneAction.Move) }, HatchIcons.Move)
            HatchMenuItem(if (zoomed) "Return to layout" else "Zoom pane", { action(PaneAction.Zoom) },
                if (zoomed) HatchIcons.RestorePanes else HatchIcons.ZoomPane)
            HatchMenuItem("Close pane", { action(PaneAction.Close) }, HatchIcons.Close, color = chrome.accent)
        }
    }
}

internal val PaneView.icon: ImageVector get() = when (this) {
    PaneView.Auto -> HatchIcons.LayoutAuto
    PaneView.Focus -> HatchIcons.LayoutFocus
    PaneView.Fit -> HatchIcons.LayoutFit
    PaneView.Scroll -> HatchIcons.LayoutColumns
}

internal val StripHeight = 32.dp
private const val SURFACE_FADE = 0.8f
private const val LIGHT_SURFACE_FADE = 0.98f
private const val CONTENT_FADE = 0.7f
