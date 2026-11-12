package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.SelectedCheck
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.allBlocks
import dev.mahali.hatch.core.sessionTarget
import dev.mahali.hatch.core.title
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.ProgramTile
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchListCard
import dev.mahali.hatch.design.HatchListDivider
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchSheet
import dev.mahali.hatch.design.HatchSheetHeader
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.RowChevron
import dev.mahali.hatch.design.SectionHeader
import dev.mahali.hatch.design.chrome

@Composable
internal fun SessionSheet(
    state: AppState,
    current: WindowRef,
    preferGenerated: Boolean,
    onOpenTab: (WindowRef) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    var switching by remember { mutableStateOf(false) }
    HatchSheet(onDismiss, container = chrome.background, skipPartiallyExpanded = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Hatch.space.xxl)) {
            if (switching) SessionsPage(state, current, { switching = false }, onOpenTab)
            else TabsPage(state, current, preferGenerated, onOpenTab, onNewTab, { switching = true }, onDismiss)
        }
    }
}

@Composable
private fun TabsPage(
    state: AppState,
    current: WindowRef,
    preferGenerated: Boolean,
    onOpenTab: (WindowRef) -> Unit,
    onNewTab: () -> Unit,
    onSwitch: () -> Unit,
    onClose: () -> Unit,
) {
    val host = state.byHost[current.host] ?: return
    val session = host.sessionsById[current.session] ?: return
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.SectionHeaderStart, end = Dimens.ScreenInset - Hatch.space.xs, bottom = Dimens.HeaderGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(host.host.name, style = HatchType.Footnote, color = chrome.secondary)
            Text(session.label, style = HatchType.Title, color = chrome.text)
        }
        HeaderButton(HatchIcons.Close, "Close", onClose)
    }
    HatchListCard {
        session.windows.mapNotNull { host.windowsById[it] }.forEachIndexed { i, window ->
            if (i > 0) HatchListDivider()
            val ref = WindowRef(current.host, current.session, window.id)
            HatchListRow(
                host.title(window, preferGenerated).primary,
                leading = { ProgramTile(host.program(window), Dimens.LeadingWidth, stacked = window.allBlocks().size > 1) },
                trailing = { if (window.id == current.window) SelectedCheck(Modifier.semantics { contentDescription = "Current tab" }) },
                onClick = { onOpenTab(ref) },
            )
        }
    }
    Spacer(Modifier.height(Dimens.IconGap))
    HatchListCard {
        HatchListRow("New Tab", leading = { SlotIcon(HatchIcons.Plus) }, onClick = onNewTab)
        HatchListDivider()
        HatchListRow("Switch Session", leading = { SlotIcon(HatchIcons.Sessions) }, trailing = { RowChevron() }, onClick = onSwitch)
    }
}

@Composable
private fun SessionsPage(state: AppState, current: WindowRef, onBack: () -> Unit, onOpenTab: (WindowRef) -> Unit) {
    HatchSheetHeader(
        "Switch Session", Modifier.padding(horizontal = Dimens.ScreenInset - Hatch.space.xs),
        leading = { HeaderButton(HatchIcons.ChevronLeft, "Back", onBack) },
    )
    for (hostId in state.hosts) {
        val host = state.byHost[hostId] ?: continue
        val online = host.link == Link.Online
        SectionHeader(host.host.name, top = Dimens.HeaderGap)
        // Offline hosts stay listed, dimmed, so their sessions remain visible.
        HatchListCard(Modifier.alpha(if (online) 1f else 0.55f)) {
            host.sessions.mapNotNull { host.sessionsById[it] }.forEachIndexed { i, session ->
                if (i > 0) HatchListDivider(Dimens.CardPadding)
                val target = host.sessionTarget(session.id)
                HatchListRow(
                    session.label,
                    trailing = {
                        when {
                            session.id == current.session && hostId == current.host -> SelectedCheck(Modifier.semantics { contentDescription = "Current session" })
                            !online -> Text("Offline", style = HatchType.Footnote, color = chrome.secondary)
                        }
                    },
                    onClick = target?.let { { onOpenTab(it) } },
                )
            }
        }
        Spacer(Modifier.height(Hatch.space.xs))
    }
}

// Leading slot keeps action titles aligned with tab titles.
@Composable
private fun SlotIcon(icon: ImageVector) = Icon(icon, null, Modifier.size(Dimens.Icon), tint = chrome.text)

internal fun HostState.program(window: Window): ProgramKey =
    (window.focused ?: window.allBlocks().firstOrNull())?.let { blocksById[it]?.program } ?: ProgramKey.Terminal
