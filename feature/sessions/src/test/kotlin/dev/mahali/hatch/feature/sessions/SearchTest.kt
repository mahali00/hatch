package dev.mahali.hatch.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val fish = pane("block:fish", "fish", dir = "/Users/me/Developer/rex-android")
    private val vim = pane("block:vim", "nvim", dir = "/Users/me/notes", title = "SessionsScreen.kt")
    private val codex = pane("block:codex", "codex", title = "Migrate auth tests")
    private val state = host(
        session("s1", "rex-android-rebuild") to listOf(tab("w1", fish), tab("w2", vim)),
        session("s2", "api-cleanup") to listOf(tab("w3", codex)),
    ).withBlocks(fish, vim, codex)

    private fun titles(query: String) = state.search(query, preferGenerated = false).associate { m -> m.session.label to m.tabs.map { it.title } }

    @Test fun `a directory fragment finds the shell in it`() {
        assertEquals(mapOf("rex-android-rebuild" to listOf("fish")), titles("developer"))
    }

    @Test fun `a matching session name keeps all its tabs`() {
        assertEquals(mapOf("rex-android-rebuild" to listOf("fish", "SessionsScreen.kt")), titles("REBUILD"))
    }

    @Test fun `tabs match on title, program and pane process`() {
        assertEquals(mapOf("api-cleanup" to listOf("Migrate auth tests")), titles("auth"))
        assertEquals(mapOf("rex-android-rebuild" to listOf("SessionsScreen.kt")), titles("neovim"))
        assertEquals(mapOf("rex-android-rebuild" to listOf("SessionsScreen.kt")), titles("nvim"))
    }

    @Test fun `nothing matching leaves nothing`() {
        assertTrue(state.search("zzz", preferGenerated = false).isEmpty())
    }

    @Test fun `match ranges find every occurrence ignoring case`() {
        assertEquals(listOf(2..7, 9..14), matchRanges("./gradle gradle", "Gradle"))
        assertEquals(emptyList<IntRange>(), matchRanges("text", " "))
    }

}
