package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchSearchField
import dev.mahali.hatch.design.LocalListMetrics

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import dev.mahali.hatch.core.SessionId
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import dev.mahali.hatch.design.ListMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.RexActions
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.design.HatchRenameDialog
import dev.mahali.hatch.design.chrome

// Room under the last row for the floating bar: 47 tall, 16 above the navigation bar, and a gap.
private val BarClearance = 80.dp

private const val MENU_SCRIM_ALPHA = 0.35f
private const val AGE_REFRESH_MILLIS = 30_000L

@Composable
public fun SessionsScreen(
    state: StateFlow<AppState>,
    settings: SettingsStore,
    actions: RexActions,
    onOpenTab: (WindowRef) -> Unit,
    onOpenSettings: () -> Unit,
    onEditHost: (HostId) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPane: (WindowRef, BlockId) -> Unit = { ref, _ -> onOpenTab(ref) },
    sidebar: Boolean = false,
    selected: WindowRef? = null,
    paused: Boolean = false,
    onCommands: (() -> Unit)? = null,
) {
    val prefs by settings.settings.collectAsState()
    var activity by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsedGroups by rememberSaveable { mutableStateOf(listOf<ActivityGroup>()) }
    var renaming by remember { mutableStateOf<RenameTarget?>(null) }
    var sessionMenu by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    // The field lets go of focus, so a terminal beside the sidebar takes the hardware keys back.
    val focus = LocalFocusManager.current
    val closeSearch: () -> Unit = {
        searching = false
        query = ""
        keyboard?.hide()
        focus.clearFocus()
    }
    val openTab: (WindowRef) -> Unit = remember(onOpenTab, focus) { { ref -> if (searching) focus.clearFocus(); onOpenTab(ref) } }
    BackHandler(enabled = searching, onBack = closeSearch)
    val now = if (activity && !paused) rememberNow(AGE_REFRESH_MILLIS) else 0L

    // Row callbacks are made here, not in the LazyColumn builder, so Compose keeps them across state changes.
    // The list collects only its outline, which changes rarely; tab titles tick, so each card collects its own rows.
    val outline = state.collectUnlessPaused(paused) { it.outline() }
    // Search and Activity read everything, but only while they show.
    val full = if ((searching && query.isNotBlank()) || activity) state.collectUnlessPaused(paused) { it } else outline
    val toggleCollapsed = { id: SessionId -> settings.update { it.copy(collapsedSessions = it.collapsedSessions.toggled(id)) } }
    val toggleHost = { id: HostId -> settings.update { it.copy(collapsedHosts = if (id.value in it.collapsedHosts) it.collapsedHosts - id.value else it.collapsedHosts + id.value) } }
    val collapseOthers = { id: SessionId -> settings.update { it.copy(collapsedSessions = it.collapsedSessions.collapsingAllBut(id, state.value)) } }
    val rename = { target: RenameTarget -> renaming = target }
    val showSessionMenu = { open: Boolean -> sessionMenu = open }
    val toggleGroup = { group: ActivityGroup -> collapsedGroups = if (group in collapsedGroups) collapsedGroups - group else collapsedGroups + group }
    val horizontal = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    CompositionLocalProvider(LocalListMetrics provides if (sidebar) ListMetrics.Sidebar else ListMetrics.Standard) {
        Box(modifier.fillMaxSize().background(chrome.workspace)) {
            val listInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).union(horizontal)
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(listInsets)
                    .then(if (sidebar) Modifier.windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)) else Modifier),
            ) {
                if (sidebar) SidebarToolbar(activity, { activity = !activity }, onOpenSettings)
                val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val list = rememberLazyListState()
                LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = BarClearance + navigationBar)) {
                    when {
                        searching && query.isNotBlank() -> searchItems(full, query, prefs.preferGeneratedTitles, selected, openTab)
                        activity -> activityItems(
                            activityGroups(full, prefs.preferGeneratedTitles, prefs.showIdleProcesses),
                            collapsedGroups,
                            nowMillis = now,
                            onToggle = toggleGroup,
                            onOpenPane = onOpenPane,
                        )
                        else -> {
                            sessionItems(
                                outline, state, prefs.collapsedSessions, prefs.collapsedHosts, prefs.preferGeneratedTitles, selected, actions, openTab, onEditHost,
                                onToggleCollapsed = toggleCollapsed, onToggleHost = toggleHost, onCollapseOthers = collapseOthers,
                                onRename = rename, onSessionMenu = showSessionMenu,
                                sidebar = sidebar, paused = paused, list = list, haptics = prefs.haptics,
                            )
                        }
                    }
                }
            }
            val barPadding = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).union(horizontal)).padding(Hatch.space.l)
            when {
                searching && sidebar -> HatchSearchField(
                    query, { query = it }, "Search", description = "Search sessions", autoFocus = true, onClose = closeSearch,
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).union(horizontal))
                        .padding(start = Hatch.space.s, end = Hatch.space.s, bottom = Hatch.layout.imeBarGap),
                )
                searching -> HatchSearchField(
                    query, { query = it }, "Search", description = "Search sessions", autoFocus = true, onClose = closeSearch,
                    modifier = Modifier.align(Alignment.BottomCenter).then(barPadding),
                )
                sidebar -> SearchButton({ searching = true }, Modifier.align(Alignment.BottomEnd).then(barPadding))
                else -> PhoneBar(
                    activity, { activity = !activity }, onOpenSettings, { searching = true },
                    Modifier.align(Alignment.BottomCenter).then(barPadding), onCommands = onCommands,
                )
            }
            if (sessionMenu) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = MENU_SCRIM_ALPHA)))
        }
    }

    renaming?.let { target ->
        HatchRenameDialog(
            "Rename Session", target.label, fieldDescription = "Session name",
            onSave = { label ->
                actions.renameSession(target.host, target.session, label)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
internal fun <T> StateFlow<AppState>.collectUnlessPaused(paused: Boolean, vararg keys: Any?, pick: (AppState) -> T): T {
    val last = remember(this, *keys) { Last(pick(value)) }
    if (!paused) {
        last.value = remember(this, *keys) { map(pick).distinctUntilChanged() }
            .collectAsState(remember(this, *keys) { pick(value) }).value
    }
    return last.value
}

// Not snapshot state: only read back while paused, when nothing changes it.
private class Last<T>(var value: T)
