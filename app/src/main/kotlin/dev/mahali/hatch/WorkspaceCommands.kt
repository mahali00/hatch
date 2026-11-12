package dev.mahali.hatch

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.chrome
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.RexActions
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.title
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchConfirmDialog
import dev.mahali.hatch.design.HatchRenameDialog
import dev.mahali.hatch.design.HatchSheet
import dev.mahali.hatch.design.HatchSheetHeader
import dev.mahali.hatch.design.Themes
import kotlinx.coroutines.flow.StateFlow

private class WorkspaceCommand(val key: String, val title: String, val detail: String, val keepOpen: Boolean = false, val run: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkspaceCommands(
    state: StateFlow<AppState>,
    settings: Settings,
    current: WindowRef?,
    store: SettingsStore,
    actions: RexActions,
    onOpenTab: (WindowRef) -> Unit,
    onSettings: () -> Unit,
    onReconnect: (HostId) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by state.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val keyboardWasVisible = remember { imeVisible }
    val dismiss = { if (!keyboardWasVisible) keyboard?.hide(); onDismiss() }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Pair<WindowRef, String>?>(null) }
    var closing by remember { mutableStateOf<WindowRef?>(null) }
    val titles = settings.preferGeneratedTitles
    // Built when what it lists changes, not on every keystroke in the field.
    val commands = remember(state, current, titles, onSettings, onReconnect, onOpenTab, store, actions) { buildList {
        add(WorkspaceCommand("settings", "Settings", "Open app settings", run = onSettings))
        current?.let { ref -> add(WorkspaceCommand("reconnect", "Reconnect host", state.byHost[ref.host]?.host?.name.orEmpty()) { onReconnect(ref.host) }) }
        current?.let { ref ->
            val host = state.byHost[ref.host] ?: return@let
            val session = host.sessionsById[ref.session]?.label.orEmpty()
            add(WorkspaceCommand("new-tab", "New Tab", "In $session") { actions.newTab(ref.host, ref.session) })
            add(WorkspaceCommand("new-session", "New Session", "On ${host.host.name}") { actions.newSession(ref.host) })
            val window = host.windowsById[ref.window] ?: return@let
            val title = host.title(window, titles).primary
            add(WorkspaceCommand("rename-tab", "Rename Tab", "$title · $session", keepOpen = true) { renaming = ref to title })
            add(WorkspaceCommand("close-tab", "Close Tab", "$title · $session", keepOpen = true) { closing = ref })
        }
        state.hosts.forEach { id ->
            val host = state.byHost[id] ?: return@forEach
            host.sessions.forEach { sessionId ->
                val session = host.sessionsById[sessionId] ?: return@forEach
                session.windows.forEach { windowId ->
                    val window = host.windowsById[windowId] ?: return@forEach
                    val ref = WindowRef(id, sessionId, windowId)
                    add(WorkspaceCommand("tab:$ref", host.title(window, titles).primary, "Switch tab · ${host.host.name} · ${session.label}") { onOpenTab(ref) })
                }
            }
        }
        Themes.all(context).forEach { theme ->
            add(WorkspaceCommand("theme:${theme.id}:${theme.dark}", "Theme: ${theme.name}", if (theme.dark) "Dark theme" else "Light theme") {
                store.update { if (theme.dark) it.copy(darkTheme = theme.id, appearance = Settings.Appearance.Dark)
                    else it.copy(lightTheme = theme.id, appearance = Settings.Appearance.Light) }
            })
        }
    } }
    renaming?.let { (ref, title) ->
        HatchRenameDialog("Rename Tab", title, fieldDescription = "Tab name", onSave = { actions.renameTab(ref, it); dismiss() }, onDismiss = dismiss)
        return
    }
    closing?.let { ref ->
        HatchConfirmDialog(
            "Close Tab?", "Every pane in this tab closes, and the programs running in them end.", "Close Tab",
            onConfirm = { actions.closeTab(ref) }, onDismiss = dismiss,
        )
        return
    }
    val matches = remember(commands, query) { commands.filter { query.isBlank() || "${it.title} ${it.detail}".contains(query.trim(), ignoreCase = true) } }
    HatchSheet(dismiss) {
        HatchSheetHeader("Commands", trailing = { HeaderButton(HatchIcons.Close, "Close commands", dismiss) })
        HatchField(query, { query = it }, "Search commands, sessions, and themes",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Hatch.space.l, vertical = Hatch.space.s), imeAction = ImeAction.Search,
            leading = { Icon(HatchIcons.Search, null, Modifier.size(Dimens.Icon), tint = chrome.secondary) })
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = Hatch.space.xxl)) {
            if (matches.isEmpty()) item { HatchListRow("No commands found") }
            items(matches, key = { it.key }) { command -> HatchListRow(command.title, subtitle = command.detail, onClick = { if (!command.keepOpen) dismiss(); command.run() }) }
        }
    }
}
