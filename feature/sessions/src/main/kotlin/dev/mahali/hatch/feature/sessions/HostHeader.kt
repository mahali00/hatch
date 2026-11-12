package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchNotice
import dev.mahali.hatch.design.ButtonSize
import dev.mahali.hatch.design.LocalListMetrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.HostKind
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenu
import dev.mahali.hatch.design.HatchMenuDivider
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HatchSpinner
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.icon

@Composable
internal fun HostHeader(
    name: String,
    kind: HostKind,
    link: Link,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    onEditHost: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    top: Dp = 27.dp,
    paused: Boolean = false,
) {
    Row(
        modifier.padding(top = top)
            .clickable(onClickLabel = if (collapsed) "Expand" else "Collapse", onClick = onToggle)
            .padding(start = LocalListMetrics.current.inset + LocalListMetrics.current.padding, end = chevronEnd(LocalListMetrics.current.headerHeight), bottom = if (LocalListMetrics.current.quiet) 0.dp else Hatch.space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalListMetrics.current.gap),
    ) {
        Box(Modifier.size(LocalListMetrics.current.leading), contentAlignment = Alignment.Center) {
            Icon(kind.icon, contentDescription = null, modifier = Modifier.size(LocalListMetrics.current.leading * HOST_ICON_SCALE), tint = chrome.secondary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                name, Modifier.semantics { heading() }, style = LocalListMetrics.current.title, color = chrome.text.copy(alpha = LocalListMetrics.current.restingText),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            LinkStatus(link, paused)
        }
        HostMenuButton(name, onNewSession, onEditHost, onReconnect)
    }
}

private const val HOST_ICON_SCALE = 0.8f

@Composable
private fun LinkStatus(link: Link, paused: Boolean) {
    val now = if (!paused && link is Link.Offline && link.retryAtMillis != null) rememberNow(1_000, link) else 0L
    val text = linkStatus(link, now) ?: return
    Text(text, style = HatchType.Footnote, color = chrome.secondary)
}

@Composable
private fun HostMenuButton(name: String, onNewSession: () -> Unit, onEditHost: () -> Unit, onReconnect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HeaderButton(HatchIcons.MoreHorizontal, "$name options", { open = true }, tint = chrome.secondary, size = ButtonSize.Row)
        HatchMenu(open, onDismiss = { open = false }) {
            val stroke = chrome.text
            val card = chrome.card
            val newSession = remember(stroke, card) { HatchIcons.newSession(stroke, card) }
            HatchMenuItem("New Session", { open = false; onNewSession() }, newSession, tintIcon = false)
            HatchMenuDivider()
            HatchMenuItem("Edit Host", { open = false; onEditHost() }, HatchIcons.Pencil)
            HatchMenuItem("Reconnect", { open = false; onReconnect() }, HatchIcons.Reconnect)
        }
    }
}

@Composable
internal fun HostNoticeView(notice: HostNotice, host: HostState, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    val name = host.host.name
    val line = modifier.padding(start = Dimens.SectionHeaderStart, end = Dimens.SectionHeaderStart, top = Hatch.space.xs, bottom = Hatch.space.m)
    when (notice) {
        HostNotice.Loading -> Row(line, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.IconGap)) {
            HatchSpinner()
            Text("Loading Sessions on $name", style = HatchType.Callout, color = chrome.secondary)
        }
        HostNotice.Empty -> Text("No sessions", line, style = HatchType.Callout, color = chrome.secondary)
        HostNotice.Offline -> HatchNotice(
            (host.link as? Link.Offline)?.reason, modifier.card(), title = "Couldn't Connect to $name",
            action = "Retry", onAction = onReconnect,
        )
        HostNotice.Replaced -> HatchNotice(
            "$name restarted as a different server. Reconnect to load its current sessions.", modifier.card(),
            action = "Reconnect", onAction = onReconnect,
        )
    }
}

@Composable
private fun Modifier.card() = padding(start = Dimens.ScreenInset, end = Dimens.ScreenInset, top = Dimens.HeaderGap, bottom = CardGap)
