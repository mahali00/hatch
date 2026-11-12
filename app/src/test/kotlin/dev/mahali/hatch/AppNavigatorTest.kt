package dev.mahali.hatch

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigatorTest {
    private val tab = WindowRef(HostId("h"), SessionId("s"), WindowId("w"))
    private val other = tab.copy(window = WindowId("x"))

    private class FakeSettings(initial: Settings = Settings()) : SettingsStore {
        override val settings = MutableStateFlow(initial)
        override fun update(change: (Settings) -> Settings) { settings.value = change(settings.value) }
    }

    private class FakeLastTab(override var lastTab: List<String> = emptyList()) : LastTabStore

    private fun navigator(saved: LastTabStore = FakeLastTab()) = AppNavigator(FakeSettings(), saved, MutableStateFlow(AppState()))

    @Test fun lastTabRoundTrips() {
        assertEquals(tab, tab.toLastTab().toWindowRef())
        assertEquals(emptyList<String>(), null.toLastTab())
        assertNull(listOf("h", "s").toWindowRef())
    }

    @Test fun startsOnTheSavedTabAndSavesTheNextOne() {
        val saved = FakeLastTab(tab.toLastTab())
        val nav = navigator(saved)
        assertEquals(tab, nav.target)
        assertEquals(Screen.Sessions, nav.screen)
        nav.open(other)
        assertEquals(Screen.Terminal, nav.screen)
        assertEquals(other.toLastTab(), saved.lastTab)
    }

    @Test fun wideWindowsShowTheTerminalInsteadOfTheList() {
        assertEquals(Screen.Terminal, settle(Screen.Sessions, tab, wide = true))
        assertEquals(Screen.Sessions, settle(Screen.Sessions, null, wide = true))
        assertEquals(Screen.Sessions, settle(Screen.Sessions, tab, wide = false))
        assertEquals(Screen.Settings, settle(Screen.Settings, tab, wide = true))

        val nav = navigator(FakeLastTab(tab.toLastTab()))
        nav.setWide(true)
        assertEquals(Screen.Terminal, nav.screen)
        nav.openSettings()
        nav.closeSettings()
        assertEquals(Screen.Terminal, nav.screen)
    }

    @Test fun settingsBackReturnsToTheTabWhenThereIsOne() {
        val nav = navigator()
        nav.openSettings()
        nav.settingsBack()
        assertEquals(Screen.Sessions, nav.screen)
        nav.open(tab)
        nav.openSettings()
        nav.settingsBack()
        assertEquals(Screen.Terminal, nav.screen)
    }

    @Test fun aTerminalLeavingAfterAnotherTabOpenedChangesNothing() {
        val nav = navigator()
        nav.open(tab)
        nav.open(other)
        nav.leaveTerminal(tab)
        assertEquals(Screen.Terminal, nav.screen)
        nav.leaveTerminal(other)
        assertEquals(Screen.Sessions, nav.screen)
    }

    @Test fun removingTheHostClosesItsTabAndSheet() {
        val saved = FakeLastTab()
        val nav = navigator(saved)
        nav.open(tab)
        nav.editingHost = tab.host
        nav.hostsChanged(listOf(tab.host))
        assertEquals(tab, nav.target)
        nav.hostsChanged(emptyList())
        assertNull(nav.target)
        assertNull(nav.editingHost)
        assertEquals(Screen.Sessions, nav.screen)
        assertEquals(emptyList<String>(), saved.lastTab)
    }

    @Test fun pausingTheHostClosesItsTabButKeepsItsSheet() {
        val nav = navigator(FakeLastTab())
        nav.open(tab)
        nav.editingHost = tab.host
        nav.hostsChanged(listOf(tab.host), connected = emptyList())
        assertNull(nav.target)
        assertEquals(tab.host, nav.editingHost)
    }

    @Test fun `settings opens before any host is saved`() {
        assertEquals(RootView.Settings, rootView(hasHosts = false, Screen.Settings, wide = false, hasTab = false))
        assertEquals(RootView.Welcome, rootView(hasHosts = false, Screen.Sessions, wide = true, hasTab = false))
        assertEquals(RootView.Wide, rootView(hasHosts = true, Screen.Terminal, wide = true, hasTab = true))
        assertEquals(RootView.Terminal, rootView(hasHosts = true, Screen.Terminal, wide = false, hasTab = true))
        assertEquals(RootView.Sessions, rootView(hasHosts = true, Screen.Terminal, wide = false, hasTab = false))
    }

    private val pane = BlockId("p")
    private val moved = WindowRef(tab.host, SessionId("t"), WindowId("y"))

    // Tabs w, x in session s and y (holding [pane]) in session t.
    private fun hostState(link: Link = Link.Online, windows: List<WindowId> = listOf(tab.window, other.window)) = AppState(listOf(tab.host), mapOf(tab.host to HostState(
        Host(tab.host, "Mac", "https://example.invalid"), link = link, sessions = listOf(tab.session, moved.session),
        sessionsById = mapOf(tab.session to Session(tab.session, "s", windows = windows), moved.session to Session(moved.session, "t", windows = listOf(moved.window))),
        windowsById = (windows + moved.window).associateWith { Window(it, it.value, if (it == moved.window) Layout.Leaf(pane) else null) },
    )))

    @Test fun closingATabReturnsToTheLastFocusedOne() {
        val apps = MutableStateFlow(hostState(windows = listOf(tab.window, other.window, WindowId("z"))))
        val nav = AppNavigator(FakeSettings(), FakeLastTab(), apps)
        val z = tab.copy(window = WindowId("z"))
        nav.open(z)
        nav.open(moved)
        nav.open(tab)
        nav.tabGone(tab)
        assertEquals(z, nav.target) // the last focused tab in this session, not the neighbour x
        nav.tabGone(tab) // a late report once another tab is selected
        assertEquals(z, nav.target)
    }

    @Test fun aNotificationOpensThePaneInTheSessionItMovedTo() = runTest {
        val nav = AppNavigator(FakeSettings(), FakeLastTab(), MutableStateFlow(hostState()))
        var requested: PromptTarget? = null
        assertNull(nav.openNotified(tab, pane, {}, { requested = it }))
        assertEquals(PromptTarget(moved, pane), requested)
        assertEquals(moved, nav.target)
    }

    @Test fun aNotificationForAClosedPaneOrAnOfflineHostSaysWhich() = runTest {
        val online = AppNavigator(FakeSettings(), FakeLastTab(), MutableStateFlow(hostState()))
        assertEquals("This pane was closed.", online.openNotified(tab, BlockId("gone"), {}, {}))
        val offline = AppNavigator(FakeSettings(), FakeLastTab(), MutableStateFlow(hostState(Link.Connecting())))
        assertEquals("This terminal is unavailable. Reconnect and try again.", offline.openNotified(tab, pane, {}, {}))
    }
}
