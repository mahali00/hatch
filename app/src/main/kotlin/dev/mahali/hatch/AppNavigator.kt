package dev.mahali.hatch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostStore
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.afterTabClosed
import dev.mahali.hatch.core.hasTab
import dev.mahali.hatch.core.promptTarget
import dev.mahali.hatch.engine.Rex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class Screen { Sessions, Terminal, Settings }

internal enum class RootView { Settings, Welcome, Wide, Terminal, Sessions }

// Settings comes first: it opens from the welcome screen before any host is saved.
internal fun rootView(hasHosts: Boolean, screen: Screen, wide: Boolean, hasTab: Boolean): RootView = when {
    screen == Screen.Settings -> RootView.Settings
    !hasHosts -> RootView.Welcome
    wide -> RootView.Wide
    screen == Screen.Terminal && hasTab -> RootView.Terminal
    else -> RootView.Sessions
}

interface LastTabStore {
    var lastTab: List<String>
}

// Screens read navigation state and call its methods; none writes it directly.
internal class AppNavigator(
    private val settings: SettingsStore,
    private val saved: LastTabStore,
    private val state: StateFlow<AppState>,
    screen: Screen = Screen.Sessions,
    sidebarShown: Boolean = true,
) {
    var screen: Screen by mutableStateOf(screen)
        private set

    // Kept in [saved], so it survives rotation, folding and relaunch.
    var target: WindowRef? by mutableStateOf(saved.lastTab.toWindowRef())
        private set

    var sidebarShown: Boolean by mutableStateOf(sidebarShown)
        private set

    var addingHost: Boolean by mutableStateOf(false)
    var editingHost: HostId? by mutableStateOf(null)
    var commandsShown: Boolean by mutableStateOf(false)

    private var wide = false

    // Counts moves, so a notification that resolves late does not pull the user back from where they went.
    private var moves = 0L

    // Most recent first; closing a tab returns to the last one still open. In memory only.
    private val recent = ArrayDeque(listOfNotNull(target))

    fun open(ref: WindowRef) {
        moves++
        go(Screen.Terminal, ref)
    }

    fun openSettings() = go(Screen.Settings, target)

    fun settingsBack() = go(if (target != null) Screen.Terminal else Screen.Sessions, target)

    fun closeSettings() = go(Screen.Sessions, target)

    fun showList() = go(Screen.Sessions, target)

    fun leaveTerminal(from: WindowRef) {
        if (target == from) showList()
    }

    fun tabGone(closed: WindowRef, rememberedIndex: Int = 0) {
        if (target != closed) return
        val now = state.value
        recent.removeAll { it == closed || !now.hasTab(it) }
        val next = now.afterTabClosed(target, closed, rememberedIndex, recent)
        go(if (next == null) Screen.Sessions else screen, next)
    }

    /** [hosts] are the saved hosts; a tab on one that was removed or paused ([connected] lacks it) closes. */
    fun hostsChanged(hosts: Collection<HostId>, connected: Collection<HostId> = hosts) {
        if (target?.host?.let { it !in connected } == true) go(Screen.Sessions, null)
        if (editingHost?.let { it !in hosts } == true) editingHost = null
    }

    fun setWide(wide: Boolean) {
        this.wide = wide
        go(screen, target)
    }

    fun showSidebar(shown: Boolean) {
        sidebarShown = shown
    }

    private fun go(screen: Screen, target: WindowRef?) {
        val next = settle(screen, target, wide)
        if (next != this.screen || target != this.target) moves++
        this.screen = next
        if (target != this.target) {
            this.target = target
            saved.lastTab = target.toLastTab()
            if (target != null) {
                recent.remove(target)
                recent.addFirst(target)
                if (recent.size > RECENT_TABS) recent.removeLast()
            }
        }
    }

    /** Opens [ref] with [block] focused (an Activity row), or just the tab if the pane is gone. */
    fun openPane(rex: Rex, ref: WindowRef, block: BlockId) {
        val destination = rex.state.value.promptTarget(ref.host, ref.session, block)
        if (destination == null) return open(ref)
        rex.requestPane(destination)
        open(destination.window)
    }

    suspend fun openNotified(rex: Rex, ref: WindowRef, block: BlockId?): String? =
        openNotified(ref, block, { rex.sync(ref.host)?.refresh(it) }, rex::requestPane)

    // Waits for the host to come online and finds the pane in any of its sessions; returns why it could not, or null.
    // A user who moved meanwhile stays put.
    internal suspend fun openNotified(ref: WindowRef, block: BlockId?, refresh: suspend (SessionId) -> Unit, requestPane: (PromptTarget) -> Unit): String? {
        if (block == null) {
            open(ref)
            return null
        }
        val start = moves
        var closed = false
        val destination = withTimeoutOrNull(NOTIFIED_TIMEOUT_MILLIS) {
            try {
                state.first { it.byHost[ref.host]?.link == Link.Online }
                refresh(ref.session)
                val now = state.value
                (now.promptTarget(ref.host, ref.session, block)
                    ?: now.byHost[ref.host]?.sessions?.firstNotNullOfOrNull { now.promptTarget(ref.host, it, block) })
                    .also { closed = it == null }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
        }
        if (moves != start) return null
        if (destination == null) return if (closed) "This pane was closed." else "This terminal is unavailable. Reconnect and try again."
        requestPane(destination)
        open(destination.window)
        return null
    }

    suspend fun follow(context: Context, hosts: HostStore) = coroutineScope {
        launch { hosts.hosts.collect { list -> hostsChanged(list.map { it.id }, list.filter { it.enabled }.map { it.id }) } }
        settings.settings.map { it.notifications && it.stayConnected }.distinctUntilChanged()
            .collect { ConnectionService.sync(context, it) }
    }

    companion object {
        private const val NOTIFIED_TIMEOUT_MILLIS = 6_000L
        private const val RECENT_TABS = 10

        /** Keeps the step and the sidebar across rotation and process death; the tab is in [LastTabStore]. */
        fun saver(settings: SettingsStore, saved: LastTabStore, state: StateFlow<AppState>): Saver<AppNavigator, Any> = listSaver(
            save = { listOf(it.screen.name, it.sidebarShown) },
            restore = { AppNavigator(settings, saved, state, Screen.valueOf(it[0] as String), it[1] as Boolean) },
        )
    }
}

internal fun settle(screen: Screen, target: WindowRef?, wide: Boolean): Screen =
    if (wide && target != null && screen == Screen.Sessions) Screen.Terminal else screen

internal fun List<String>.toWindowRef(): WindowRef? =
    takeIf { it.size == 3 }?.let { (h, s, w) -> WindowRef(HostId(h), SessionId(s), WindowId(w)) }

internal fun WindowRef?.toLastTab(): List<String> =
    this?.let { listOf(it.host.value, it.session.value, it.window.value) }.orEmpty()
