package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.Block
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateCodecTest {
    private val host = Host(HostId("h"), "Mac", "https://mac")

    @Test fun `a saved list comes back as an unloaded copy`() {
        val a = BlockId("a"); val b = BlockId("b"); val w = WindowId("w"); val s = SessionId("s")
        val state = HostState(
            host, link = Link.Online, serverId = "server",
            sessions = listOf(s),
            sessionsById = mapOf(s to Session(s, "work", revision = 7, windows = listOf(w), activeWindow = w)),
            windowsById = mapOf(w to Window(w, "tab", Layout.Split(Axis.Row, 0.4f, Layout.Leaf(a), Layout.Leaf(b)), focused = b)),
            blocksById = mapOf(
                a to Block(a, title = "HANDOFF", processName = "claude", program = ProgramKey("claude"), owner = "me"),
                b to Block(b, processName = "fish", workingDirectory = "/Users/x"),
            ),
        )
        val back = StateCodec.decode(host, StateCodec.encode(state))!!
        assertEquals(listOf(s), back.sessions)
        assertEquals(-1L, back.sessionsById[s]!!.revision)
        assertEquals(listOf(w), back.sessionsById[s]!!.windows)
        assertEquals(state.windowsById, back.windowsById)
        assertEquals("HANDOFF", back.blocksById[a]!!.title)
        assertEquals(ProgramKey("claude"), back.blocksById[a]!!.program)
        assertNull(back.blocksById[a]!!.owner)
        assertEquals("server", back.serverId)
        assertEquals(Link.Connecting(), back.link)
    }

    @Test fun `unreadable text gives nothing`() = assertNull(StateCodec.decode(host, "{not json"))
}
