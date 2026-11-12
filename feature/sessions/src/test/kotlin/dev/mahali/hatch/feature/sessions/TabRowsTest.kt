package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.StatusMark
import dev.mahali.hatch.core.StatusRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabRowsTest {
    private val s = session("s1", "work")

    @Test fun `blocked panes do not borrow an unrelated pane's legacy progress`() {
        val working = pane("b1", "claude").copy(status = mapOf("r" to StatusRecord("r", ProgramState.Blocked)))
        val building = pane("b2", "fish").copy(progress = 40)
        val window = tab("w1", working, building)
        val row = host(s to listOf(window)).withBlocks(working, building).tabRow(s.id, window, preferGenerated = false)
        assertTrue(row.stacked)
        assertEquals(StatusMark.Blocked, row.mark)
        assertEquals(null, row.progress)
    }

    @Test fun `tab rows expose record progress and blocked reason`() {
        val agent = pane("b", "claude").copy(progress = 90, status = mapOf("job" to StatusRecord("job", ProgramState.Blocked,
            progress = 37, kind = dev.mahali.hatch.core.BlockedKind.Permission)))
        val window = tab("w", agent)
        val row = host(s to listOf(window)).withBlocks(agent).tabRow(s.id, window, false)
        assertEquals(37, row.progress)
        assertEquals(dev.mahali.hatch.core.BlockedKind.Permission, row.kind)
    }
}
