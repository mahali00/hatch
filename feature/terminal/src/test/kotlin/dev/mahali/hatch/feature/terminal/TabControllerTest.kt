package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.engine.createTabOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class TabControllerTest {
    private val hostId = HostId("h")
    private val sessionId = SessionId("s")
    private val w1 = WindowId("w1")
    private val w2 = WindowId("w2")
    private val w3 = WindowId("w3")
    private val a = BlockId("a")
    private val b = BlockId("b")
    private val f = BlockId("f")
    private val pair = Layout.Split(Axis.Row, 0.5f, Layout.Leaf(a), Layout.Leaf(b))
    private val target = WindowRef(hostId, sessionId, w2)

    private val opened = mutableListOf<WindowRef>()
    private var backs = 0
    private val gone = mutableListOf<Pair<WindowRef, Int>>()
    private val said = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun closeScope() {
        scope.cancel()
    }

    private fun state(windows: List<WindowId> = listOf(w1, w2, w3), window: Window? = Window(w2, "", pair, listOf(f), focused = b)) = AppState(
        listOf(hostId),
        mapOf(hostId to HostState(
            Host(hostId, "h", "url"), link = Link.Online,
            sessionsById = mapOf(sessionId to Session(sessionId, "", revision = 1, windows = windows)),
            windowsById = window?.let { mapOf(it.id to it) }.orEmpty(),
        )),
    )

    private fun tab(flow: MutableStateFlow<AppState> = MutableStateFlow(state()), wide: Boolean = true) = TabController(
        target, wide, flow, scope,
        operations = createTabOperations(target, api = { null }, sync = { null }, startup = { "" }),
        say = { said += it }, onOpenTab = { opened += it }, onBack = { backs++ }, onSplit = {},
        onTabGone = { ref, index -> gone += ref to index }, onNewTab = {},
    ).apply { area = DpSize(900.dp, 700.dp) }

    @Test fun `wide windows tile while every tile has room, and page otherwise`() {
        val t = tab()
        assertTrue(t.tiled)
        assertEquals(listOf(a, b), t.panes)
        assertEquals(Tiles, t.mode)
        t.area = DpSize(300.dp, 700.dp)
        assertFalse(t.tiled)
        assertEquals(listOf(a, b, f), t.panes) // floating panes page too
        assertEquals(Pages(listOf(a, b, f)), t.mode)
        t.view = PaneView.Fit
        assertTrue(t.tiled)
        assertFalse(tab(wide = false).tiled)
    }

    @Test fun `current is the chosen pane while it shows, else the server's focus`() {
        val t = tab()
        assertEquals(b, t.current)
        t.chosen = a
        assertEquals(a, t.current)
        t.chosen = f // floating: not a tile
        assertEquals(b, t.current)
    }

    @Test fun `a page switch settles on its target when the finger lifts, never mid-drag`() {
        val t = tab(wide = false)
        assertEquals(b, t.settled(paging = false, dragging = false, targetPage = 0))
        assertNull(t.settled(paging = true, dragging = true, targetPage = 2))
        assertEquals(f, t.settled(paging = true, dragging = false, targetPage = 2))
        assertEquals(listOf(f), t.claims(f) { emptyList() })
    }

    @Test fun `obsolete swipe cannot claim the old index after programmatic selection`() {
        val t = tab(wide = false)
        val boundary = t.pagerBoundary()
        t.pending = a
        assertEquals(a, t.settled(paging = true, dragging = false, targetPage = 2, boundary = boundary))
        t.swiped(boundary, 2)
        assertEquals(a, t.current)
    }

    @Test fun `a new swipe cancels pending animation without losing its destination on finger lift`() {
        val t = tab(wide = false)
        t.pending = a
        val animation = requireNotNull(t.transition)
        val swipe = t.beginSwipe()
        assertNull(t.pending)
        assertEquals(f, t.settled(paging = true, dragging = false, targetPage = 2, boundary = swipe))
        t.swiped(swipe, 2)
        t.complete(animation)
        assertEquals(f, t.current)
    }

    @Test fun `tiles claim every pane, scrollable columns the visible ones`() {
        val t = tab()
        assertEquals(b, t.settled(paging = true, dragging = true, targetPage = 0))
        assertEquals(listOf(a, b), t.claims(null) { listOf(a) })
        t.view = PaneView.Scroll
        assertEquals(listOf(a), t.claims(null) { listOf(a) })
    }

    @Test fun `zoom leaves the tiles for the pane and comes back to the view it left`() {
        val t = tab()
        t.view = PaneView.Scroll
        t.action(a, PaneAction.Zoom)
        assertEquals(PaneView.Focus, t.view)
        assertEquals(a, t.pending)
        assertFalse(t.tiled)
        t.action(a, PaneAction.Zoom)
        assertEquals(PaneView.Scroll, t.view)
    }

    @Test fun `acting on automatic tiles keeps them, and a failed split says so`() {
        val t = tab()
        t.split(a, below = false)
        assertEquals(PaneView.Fit, t.view)
        assertEquals(listOf("Could not split pane: Host is not connected"), said)
    }

    @Test fun `opening another tab navigates locally even when server focus fails`() {
        val next = WindowRef(hostId, sessionId, w3)
        tab().openTab(next)

        assertEquals(listOf(next), opened)
        assertEquals(listOf("Could not focus tab: Host is not connected"), said)
    }

    @Test fun `opening the current tab makes no request`() {
        tab().openTab(target)

        assertTrue(opened.isEmpty())
        assertTrue(said.isEmpty())
    }

    @Test fun `a disconnected close reports failure without removing another pane locally`() {
        val t = tab()
        t.close(a)

        assertEquals(listOf("Could not close pane: Host is not connected"), said)
        assertEquals(listOf(a, b), t.panes)
        assertTrue(opened.isEmpty())
        assertEquals(0, backs)
    }

    @Test fun `a tab that goes after it showed reports its source and remembered index once`() {
        val flow = MutableStateFlow(state())
        tab(flow)
        flow.value = state(windows = listOf(w1, w3), window = null)
        flow.value = state(windows = listOf(w1), window = null)
        assertEquals(listOf(target to 1), gone)
        assertTrue(opened.isEmpty())
    }

    @Test fun `closing the last pane reports disappearance once without choosing a destination`() {
        val one = Window(w2, "", Layout.Leaf(a), focused = a)
        val t = tab(MutableStateFlow(state(windows = listOf(w2), window = one)))
        t.close(a)
        t.close(a)
        assertEquals(listOf(target to 0), gone)
        assertEquals(0, backs)
        assertTrue(opened.isEmpty())
    }

    @Test fun `optimistic close and server removal report one disappearance`() {
        val one = Window(w2, "", Layout.Leaf(a), focused = a)
        val flow = MutableStateFlow(state(window = one))
        val t = tab(flow)
        t.close(a)
        flow.value = state(windows = listOf(w1, w3), window = null)
        t.tabGone()
        assertEquals(listOf(target to 1), gone)
    }

}
