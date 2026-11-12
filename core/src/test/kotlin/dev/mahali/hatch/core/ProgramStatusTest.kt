package dev.mahali.hatch.core

import org.junit.Assert.*
import org.junit.Test

class ProgramStatusTest {
    private val id = BlockId("pane")
    private fun pane(vararg records: StatusRecord) = Block(id, status = records.associateBy { it.id })

    @Test fun `blocked child remains distinct while its parent is idle`() {
        val b = pane(StatusRecord("", ProgramState.Idle), StatusRecord("build/test", ProgramState.Blocked, kind = BlockedKind.Question))
        assertEquals(StatusMark.Blocked, b.statusMark())
        assertEquals(2, b.status.size)
    }

    @Test fun `indeterminate program status suppresses stale legacy percentage`() {
        val b = pane(StatusRecord("job", ProgramState.Working)).copy(progress = 80)
        assertNull(b.statusProgress())
        assertEquals(37, b.copy(status = mapOf("job" to StatusRecord("job", ProgramState.Working, progress = 37))).statusProgress())
        assertEquals(80, b.copy(status = emptyMap()).statusProgress())
    }

    @Test fun `app inheritance uses the nearest existing ancestor and tracks replacements`() {
        val root = StatusRecord("", ProgramState.Idle, app = "claude")
        val child = StatusRecord("build/missing/test", ProgramState.Working, effectiveApp = "old-app")
        val b = pane(root, StatusRecord("build", ProgramState.Working, app = "cargo"), child)
        assertEquals("cargo", b.statusApp(child))
        assertEquals("claude", b.copy(status = b.status - "build").statusApp(child))
        assertEquals("other", b.statusApp(child.copy(app = "other")))
        assertEquals("old-app", pane(child).statusApp(child))
    }

    @Test fun `record replacement removes omitted message progress and blocked kind`() {
        val old = StatusRecord("job", ProgramState.Blocked, message = "Approve?", progress = 37, kind = BlockedKind.Permission)
        val h = HostState(Host(HostId("h"), "Host", "https://example.com"), blocksById = mapOf(id to pane(old)))
        val next = h.reduce(HostEvent.StatusChanged(id, StatusRecord("job", ProgramState.Idle))).state.blocksById[id]!!.status.getValue("job")
        assertEquals("", next.message)
        assertNull(next.progress)
        assertNull(next.kind)
    }

    @Test fun `process exit drops active tasks and retains completed and failed records`() {
        val records = listOf(StatusRecord("work", ProgramState.Working), StatusRecord("wait", ProgramState.Blocked),
            StatusRecord("done", ProgramState.Done), StatusRecord("failed", ProgramState.Error))
        val h = HostState(Host(HostId("h"), "Host", "https://example.com"), blocksById = mapOf(id to pane(*records.toTypedArray())))
        val b = h.reduce(HostEvent.ChildExited(id)).state.blocksById.getValue(id)
        assertTrue(b.exited)
        assertEquals(setOf("done", "failed"), b.status.keys)
    }
}
