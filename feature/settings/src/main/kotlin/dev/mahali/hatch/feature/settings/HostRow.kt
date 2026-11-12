package dev.mahali.hatch.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.design.Chrome
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.icon

internal fun Chrome.statusColor(link: Link?): Color = when (link) {
    Link.Online -> online
    is Link.Offline -> stroke
    is Link.Connecting, null -> warning
}

@Composable
internal fun HostRow(
    host: Host,
    link: Link?,
    modifier: Modifier = Modifier,
    handle: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    // A paused host is dimmed, not hidden: it stays here to be switched back on.
    val nameColor = if (host.enabled) chrome.text else chrome.tertiary
    HatchListRow(
        modifier,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                handle?.invoke()
                Icon(host.kind.icon, null, Modifier.size(Dimens.Icon), tint = nameColor)
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (host.enabled && link != Link.Online) Box(Modifier.size(8.dp).clip(CircleShape).background(chrome.statusColor(link)))
                    Text(statusWord(host, link), style = HatchType.Callout, color = chrome.secondary)
                }
                trailing()
            }
        },
    ) {
        Text(host.name, style = HatchType.Body, color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(host.url.substringAfter("://"), style = HatchType.Callout, color = chrome.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
