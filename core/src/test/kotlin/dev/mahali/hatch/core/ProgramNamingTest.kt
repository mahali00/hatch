package dev.mahali.hatch.core

import org.junit.Test
import org.junit.Assert.assertEquals

class ProgramNamingTest {
    @Test fun `a reported app picks the program over the process`() {
        val block = Block(BlockId("b"), processName = "node",
            status = mapOf("s" to StatusRecord("s", ProgramState.Done, app = "pi"))).withProgram()
        assertEquals(ProgramKey("pi"), block.program)
    }

}
