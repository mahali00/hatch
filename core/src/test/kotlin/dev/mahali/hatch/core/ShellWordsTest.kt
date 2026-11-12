package dev.mahali.hatch.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellWordsTest {
    @Test fun `splits on whitespace`() = assertEquals(listOf("tmux", "new", "-A"), shellWords("  tmux  new -A "))

    @Test fun `keeps quoted spaces`() =
        assertEquals(listOf("bash", "-lc", "cd ~/work && claude"), shellWords("bash -lc 'cd ~/work && claude'"))

    @Test fun `double quotes honour escapes`() = assertEquals(listOf("echo", "say \"hi\""), shellWords("echo \"say \\\"hi\\\"\""))

    @Test fun `backslash escapes a space`() = assertEquals(listOf("open", "My File"), shellWords("open My\\ File"))

    @Test fun `empty quotes make an empty word`() = assertEquals(listOf("a", "", "b"), shellWords("a '' b"))

    @Test fun `blank is no words`() = assertEquals(emptyList<String>(), shellWords("   "))
}
