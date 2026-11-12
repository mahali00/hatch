package dev.mahali.hatch.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramKeyTest {
    private fun key(process: String) = programKey(process).value

    @Test fun `paths, login shells and case are normalized`() {
        assertEquals("zsh", key("-zsh"))
        assertEquals("git", key("/usr/bin/git"))
        assertEquals("python", key("Python3.12"))
    }

}
