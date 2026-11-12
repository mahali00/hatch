package dev.mahali.hatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerTest {
    private val host = HostState(Host(HostId("h"), "studio", "https://studio.example.ts.net"))
    private val s1 = SessionId("session:1")
    private val s2 = SessionId("session:2")
    private val w1 = WindowId("window:1")
    private val a = BlockId("block:a")
    private val b = BlockId("block:b")

    private fun view(revision: Long, vararg ids: String): SessionView {
        val blocks = ids.map(::BlockId)
        val window = WindowView(w1, "Window 0000abcd", blocks.toLayout(), emptyList(), blocks.firstOrNull(),
            blocks.map { BlockInfo(it, "shell", null) })
        return SessionView(s1, "work", revision, w1, listOf(window))
    }

    private fun List<BlockId>.toLayout(): Layout? = when (size) {
        0 -> null
        1 -> Layout.Leaf(first())
        else -> Layout.Split(Axis.Row, 0.5f, Layout.Leaf(first()), drop(1).toLayout()!!)
    }

    private fun HostState.apply(vararg events: HostEvent): HostState = events.fold(this) { s, e -> s.reduce(e).state }

    @Test fun `bootstrap lists sessions in server order and asks for their views`() {
        val r = host.reduce(HostEvent.Bootstrapped("client:me", "server-1", listOf(s1 to "work", s2 to "play")))
        assertEquals(listOf(s1, s2), r.state.sessions)
        assertEquals(Link.Online, r.state.link)
        assertEquals(listOf(Effect.LoadView(s1), Effect.LoadView(s2)), r.effects)
    }

    @Test fun `a view fills windows and blocks and asks for new blocks' details`() {
        val r = host.reduce(HostEvent.ViewLoaded(view(3, "block:a", "block:b")))
        assertEquals(listOf(w1), r.state.sessionsById[s1]!!.windows)
        assertEquals(setOf(a, b), r.state.blocksById.keys)
        assertEquals(listOf(Effect.LoadBlockDetails(s1, a), Effect.LoadBlockDetails(s1, b)), r.effects)
    }

    @Test fun `an older view is ignored`() {
        val state = host.apply(HostEvent.ViewLoaded(view(5, "block:a", "block:b")), HostEvent.ViewLoaded(view(4, "block:a")))
        assertEquals(setOf(a, b), state.blocksById.keys)
        assertEquals(5L, state.sessionsById[s1]!!.revision)
    }

    @Test fun `a newer view removes closed blocks but keeps known details`() {
        val state = host.apply(
            HostEvent.ViewLoaded(view(1, "block:a", "block:b")),
            HostEvent.TitleChanged(a, "vim"),
            HostEvent.ViewLoaded(view(2, "block:a")),
        )
        assertEquals(setOf(a), state.blocksById.keys)
        assertEquals("vim", state.blocksById[a]!!.title)
    }

    @Test fun `view changes load only when newer than the view held`() {
        val state = host.apply(HostEvent.ViewLoaded(view(4, "block:a")))
        assertTrue(state.reduce(HostEvent.ViewChanged(s1, 4)).effects.isEmpty())
        assertEquals(listOf(Effect.LoadView(s1)), state.reduce(HostEvent.ViewChanged(s1, 5)).effects)
    }

    @Test fun `sessions move, rename and disappear`() {
        val state = host.apply(
            HostEvent.Bootstrapped("c", "srv", listOf(s1 to "work", s2 to "play")),
            HostEvent.SessionMoved(s2, s1),
            HostEvent.SessionLabelChanged(s1, "deep work"),
        )
        assertEquals(listOf(s2, s1), state.sessions)
        assertEquals("deep work", state.sessionsById[s1]!!.label)
        val gone = state.apply(HostEvent.ViewLoaded(view(1, "block:a")), HostEvent.SessionDestroyed(s1))
        assertEquals(listOf(s2), gone.sessions)
        assertTrue(gone.blocksById.isEmpty())
    }

    @Test fun `a session the server no longer lists loses its tabs, even one known only from the saved state`() {
        val saved = host.apply(HostEvent.ViewLoaded(view(1, "block:a")))
        val state = saved.apply(HostEvent.Bootstrapped("c", "srv", listOf(s2 to "play")))
        assertTrue(state.windowsById.isEmpty())
        assertTrue(state.blocksById.isEmpty())
    }

    @Test fun `a different server after reconnecting drops what was cached`() {
        val state = host.apply(
            HostEvent.Bootstrapped("c", "srv-1", listOf(s1 to "work")),
            HostEvent.ViewLoaded(view(1, "block:a")),
            HostEvent.Bootstrapped("c", "srv-2", listOf(s2 to "fresh")),
        )
        assertTrue(state.replacedServer)
        assertEquals(listOf(s2), state.sessions)
        assertTrue(state.blocksById.isEmpty())
    }

    @Test fun `process, pwd and status update a pane`() {
        val state = host.apply(
            HostEvent.ViewLoaded(view(1, "block:a")),
            HostEvent.ProcessChanged(a, "-zsh"),
            HostEvent.PwdChanged(a, "file://studio/Users/me/Developer/rex%20pocket"),
            HostEvent.StatusChanged(a, StatusRecord("", ProgramState.Blocked)),
        )
        val pane = state.blocksById[a]!!
        assertEquals("zsh", pane.program.value)
        assertEquals(Title("zsh", "~/Developer/rex pocket"), pane.title())
        assertEquals(StatusMark.Blocked, pane.statusMark())
        assertFalse(state.reduce(HostEvent.StatusRemoved(a, listOf(""))).state.blocksById[a]!!.status.isNotEmpty())
    }

    @Test fun `a tab uses its label only when the user set one`() {
        val state = host.apply(HostEvent.ViewLoaded(view(1, "block:a")), HostEvent.TitleChanged(a, "htop"), HostEvent.ProcessChanged(a, "htop"))
        val window = state.windowsById[w1]!!
        assertEquals(Title("htop"), state.title(window))
        assertEquals(Title("server"), state.title(window.copy(label = "server")))
    }
}
