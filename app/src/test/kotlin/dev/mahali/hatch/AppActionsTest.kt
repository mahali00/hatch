package dev.mahali.hatch

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostStore
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppActionsTest {
    private val host = HostId("host")
    private val session = SessionId("session")
    private val hosts = object : HostStore {
        override val hosts = MutableStateFlow<List<Host>>(emptyList())
        override fun save(host: Host) = Unit
        override fun remove(id: HostId) = Unit
        override fun reorder(ids: List<HostId>) = Unit
    }

    @Test fun `disconnected app commands report failure instead of silently returning`() = runTest {
        val actions = AppActions(MutableStateFlow(AppState()), { null }, {}, hosts, backgroundScope)
        val failures = mutableListOf<String>()
        val opened = mutableListOf<WindowRef>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.failures.collect { failures += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.opens.collect { opened += it } }

        actions.newTab(host, session)
        actions.closeTab(WindowRef(host, session, WindowId("window")))
        actions.placeTab(WindowRef(host, session, WindowId("window")), null)
        runCurrent()

        assertEquals(listOf(
            "Couldn't Create Tab: Host is not connected",
            "Couldn't Close Tab: Host is not connected",
            "Couldn't Move Tab: Host is not connected",
        ), failures)
        assertTrue(opened.isEmpty())
    }

}
