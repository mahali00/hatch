package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.design.Hatch
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.RexActions
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.SectionHeader
import dev.mahali.hatch.design.chrome

internal data class RenameTarget(val host: HostId, val session: SessionId, val label: String)

private val FirstHostTop = 11.dp

private const val OFFLINE_ALPHA = 0.5f

internal fun LazyListScope.sessionItems(
    state: AppState,
    live: StateFlow<AppState>,
    collapsed: Set<String>,
    collapsedHosts: Set<String>,
    preferGenerated: Boolean,
    selected: WindowRef?,
    actions: RexActions,
    onOpenTab: (WindowRef) -> Unit,
    onEditHost: (HostId) -> Unit,
    onToggleCollapsed: (SessionId) -> Unit,
    onToggleHost: (HostId) -> Unit,
    onCollapseOthers: (SessionId) -> Unit,
    onRename: (RenameTarget) -> Unit,
    onSessionMenu: (Boolean) -> Unit,
    sidebar: Boolean,
    paused: Boolean,
    list: LazyListState,
    haptics: Boolean,
) {
    state.hosts.forEachIndexed { index, hostId ->
        val host = state.byHost[hostId] ?: return@forEachIndexed
        val hostCollapsed = hostId.value in collapsedHosts
        item(key = "host:${hostId.value}", contentType = "host") {
            HostHeader(
                host.host.name, host.host.kind, host.link, hostCollapsed,
                onToggle = { onToggleHost(hostId) },
                onNewSession = { actions.newSession(hostId) },
                onEditHost = { onEditHost(hostId) },
                onReconnect = { actions.reconnect(hostId) },
                // iOS: title centre 97 pt, safe area 62 pt, so 35 below the safe top and 11 above the 48 row.
                top = if (index > 0) (if (sidebar) 12.dp else 20.dp) else if (sidebar) 0.dp else FirstHostTop,
                paused = paused,
            )
        }
        if (hostCollapsed) return@forEachIndexed
        host.notice()?.let { notice ->
            item(key = "notice:${hostId.value}", contentType = "notice") {
                HostNoticeView(notice, host, onReconnect = { actions.reconnect(hostId) })
            }
        }
        val rowAlpha = if (host.link is Link.Offline) OFFLINE_ALPHA else 1f
        host.sessions.forEach { sessionId ->
            val session = host.sessionsById[sessionId] ?: return@forEach
            val isCollapsed = sessionId.value in collapsed
            // Plain values only: Session objects are rebuilt on every update, so every header's callbacks would be new.
            val label = session.label
            item(key = "session:${hostId.value}:${sessionId.value}", contentType = "session") {
                SessionHeader(
                    label, isCollapsed,
                    onToggle = { onToggleCollapsed(sessionId) },
                    onNewTab = { actions.newTab(hostId, sessionId) },
                    onRename = { onRename(RenameTarget(hostId, sessionId, label)) },
                    onCollapseOthers = { onCollapseOthers(sessionId) },
                    onClose = { actions.closeSession(hostId, sessionId) },
                    modifier = Modifier.alpha(rowAlpha),
                    onMenuShown = onSessionMenu,
                )
            }
            if (!isCollapsed) {
                val tabsKey = "tabs:${hostId.value}:${sessionId.value}"
                item(key = tabsKey, contentType = "tabs") {
                    LiveTabCard(
                        live, paused, hostId, sessionId, preferGenerated, selected, onOpenTab, actions, onSessionMenu,
                        Modifier.alpha(rowAlpha).padding(bottom = if (sidebar) Hatch.space.xs else CardGap), list, tabsKey, haptics,
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.searchItems(
    state: AppState,
    query: String,
    preferGenerated: Boolean,
    selected: WindowRef?,
    onOpenTab: (WindowRef) -> Unit,
) {
    state.hosts.forEach { hostId ->
        val host = state.byHost[hostId] ?: return@forEach
        val matches = host.search(query, preferGenerated)
        val count = matches.sumOf { it.tabs.size }
        item(key = "search-host:${hostId.value}", contentType = "search-host") {
            SectionHeader(resultsLabel(count, host.host.name), top = 18.dp)
        }
        if (matches.isEmpty()) {
            item(key = "search-none:${hostId.value}", contentType = "search-none") {
                Text(
                    "No Matching Panes", Modifier.padding(horizontal = Dimens.SectionHeaderStart, vertical = Hatch.space.xs),
                    style = HatchType.Callout, color = chrome.secondary,
                )
            }
        }
        matches.forEach { match ->
            val sessionKey = "${hostId.value}:${match.session.id.value}"
            item(key = "search-session:$sessionKey", contentType = "search-session") {
                SessionName(
                    match.session.label,
                    Modifier.padding(start = Dimens.SectionHeaderStart, end = Dimens.SectionHeaderStart, top = 10.dp, bottom = Dimens.HeaderGap),
                )
            }
            if (match.tabs.isNotEmpty()) {
                item(key = "search-tabs:$sessionKey", contentType = "tabs") {
                    TabCard(match.tabs, selected, onOpenTab, Modifier.padding(bottom = Hatch.space.xs), query = query)
                }
            }
        }
    }
}

internal fun resultsLabel(count: Int, hostName: String): String = when (count) {
    0 -> hostName
    1 -> "1 result on $hostName"
    else -> "$count results on $hostName"
}

internal fun AppState.outline(): AppState = copy(byHost = byHost.mapValues { (_, h) -> h.copy(blocksById = emptyMap(), windowsById = emptyMap()) })

@Composable
private fun LiveTabCard(
    live: StateFlow<AppState>,
    paused: Boolean,
    host: HostId,
    session: SessionId,
    preferGenerated: Boolean,
    selected: WindowRef?,
    onOpenTab: (WindowRef) -> Unit,
    actions: RexActions,
    onMenuShown: (Boolean) -> Unit,
    modifier: Modifier,
    list: LazyListState,
    listKey: String,
    haptics: Boolean,
) {
    fun rows(s: AppState) = s.byHost[host]?.let { h -> h.sessionsById[session]?.let { h.tabRows(it, preferGenerated) } }.orEmpty()
    val tabs = live.collectUnlessPaused(paused, host, session, preferGenerated, pick = ::rows)
    TabCard(tabs, selected, onOpenTab, modifier, actions = actions, onMenuShown = onMenuShown, list = list, listKey = listKey, haptics = haptics)
}
