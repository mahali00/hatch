package dev.mahali.hatch.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedTitleTest {
    @Test fun `generated titles replace shell titles only when enabled`() {
        val shell = Block(BlockId("shell"), processName = "-fish", title = "custom shell title", workingDirectory = "/Users/me/project")
        assertEquals(Title("custom shell title"), shell.title(false))
        assertEquals(Title("fish", "~/project"), shell.title(true))
    }

    @Test fun `applications retain their own titles with generated titles enabled`() {
        val program = Block(BlockId("program"), processName = "node", title = "Claude task", workingDirectory = "/tmp/project")
        assertEquals(Title("Claude task"), program.title(true))
    }
}
