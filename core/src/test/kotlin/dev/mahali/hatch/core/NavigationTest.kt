package dev.mahali.hatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {
    private val host = Host(HostId("host"), "Host", "https://example.com")
    private val session = SessionId("session")
    private val a = WindowId("a")
    private val b = WindowId("b")
    private val c = WindowId("c")
    private fun state(active: WindowId?, windows: List<WindowId> = listOf(a, b)) = HostState(host,
        sessions = listOf(session), sessionsById = mapOf(session to Session(session, "Session", 1, windows, active)),
        windowsById = windows.associateWith { Window(it, it.value, null) })

    @Test fun `active window event ahead of view falls back to a real local tab`() {
        assertEquals(WindowRef(host.id, session, a), state(WindowId("not-loaded-yet")).sessionTarget(session))
        assertEquals(WindowRef(host.id, session, b), state(b).sessionTarget(session))
    }

    @Test fun `empty session has no target`() { assertNull(state(null, emptyList()).sessionTarget(session)) }

    @Test fun `late exit from old tab cannot clear the newly selected tab`() {
        val old = WindowRef(host.id, session, a)
        val selected = old.copy(window = b)
        val app = AppState(listOf(host.id), mapOf(host.id to state(b)))
        assertEquals(selected, app.afterTabClosed(selected, old))
        assertEquals(selected, app.afterTabClosed(old, old))
    }

    @Test fun `B removal from A B C picks C both before and after server removal`() {
        val closed = WindowRef(host.id, session, b)
        val expected = closed.copy(window = c)
        val before = AppState(listOf(host.id), mapOf(host.id to state(b, listOf(a, b, c))))
        val after = AppState(listOf(host.id), mapOf(host.id to state(c, listOf(a, c))))
        assertEquals(expected, before.afterTabClosed(closed, closed, 1))
        assertEquals(expected, after.afterTabClosed(closed, closed, 1))
        assertEquals(expected, after.afterTabClosed(expected, closed, 1))
    }

    @Test fun `last tab removal uses previous neighbour and empty session clears selection`() {
        val closed = WindowRef(host.id, session, c)
        val after = AppState(listOf(host.id), mapOf(host.id to state(b, listOf(a, b))))
        assertEquals(closed.copy(window = b), after.afterTabClosed(closed, closed, 2))
        val empty = AppState(listOf(host.id), mapOf(host.id to state(null, emptyList())))
        assertNull(empty.afterTabClosed(closed, closed, 2))
    }

    @Test fun `closing returns to the most recent open tab, same session before another session`() {
        val other = SessionId("other")
        val d = WindowId("d")
        val two = state(c, listOf(a, b, c)).let {
            it.copy(sessions = listOf(session, other), sessionsById = it.sessionsById + (other to Session(other, "Other", 1, listOf(d), d)),
                windowsById = it.windowsById + (d to Window(d, "d", null)))
        }
        val app = AppState(listOf(host.id), mapOf(host.id to two))
        val closed = WindowRef(host.id, session, c)
        val inA = closed.copy(window = a)
        val inD = WindowRef(host.id, other, d)
        val gone = closed.copy(window = WindowId("gone"))
        assertEquals(inA, app.afterTabClosed(closed, closed, 2, listOf(closed, gone, inD, inA)))
        assertEquals(inD, app.afterTabClosed(closed, closed, 2, listOf(closed, inD)))
        assertEquals(closed.copy(window = b), app.afterTabClosed(closed, closed, 2, listOf(gone)))
    }
}
