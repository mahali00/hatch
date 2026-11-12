package dev.mahali.hatch.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahali.hatch.protocol.ForegroundProcess
import dev.mahali.hatch.protocol.PromptScreenExport
import dev.mahali.hatch.protocol.TerminalIdentity
import dev.mahali.hatch.ghostty.GhosttyTerminal
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptDecoderTest {
    private val identity = TerminalIdentity(110, 40, ForegroundProcess(1, "codex", "/bin/codex", "", "", "codex"), false, emptySet())
    private fun captured(): String = InstrumentationRegistry.getInstrumentation().context.assets.open("codex-approval.vt")
        .bufferedReader().use { it.readText() }

    @Test fun actualRexExportDecodesToActionableActiveScreen() {
        val screen = decodePromptScreen(PromptScreenExport(identity, captured()))
        val prompt = recognizePrompt(screen, PromptAgent.Codex)
        val details = screen.lines.mapIndexed { row, text -> "$row: $text" }.joinToString("\n")
        assertNotNull("cursor=${screen.cursorRow}\n$details", prompt)
        assertEquals(listOf("y", "p", ""), prompt!!.choices.map { it.text })
        assertEquals("escape", prompt.choices.last().key)
        assertEquals(true, prompt.context.contains("HATCH_REX_DIRECT_POC"))
    }

    @Test fun historicalMenuAboveANewShellPromptCannotBeAnswered() {
        val screen = decodePromptScreen(PromptScreenExport(identity, captured() + "\u001b[40;1H\r\n$ echo done\r\n\u001b[?25h"))
        assertNull(recognizePrompt(screen, PromptAgent.Codex))
    }

    @Test fun actualClaudeRexExportDecodesToItsAdvertisedNumericAnswers() {
        val vt = InstrumentationRegistry.getInstrumentation().context.assets.open("claude-approval.vt")
            .bufferedReader().use { it.readText() }
        val screen = decodePromptScreen(PromptScreenExport(identity.copy(alternate = true), vt))
        val prompt = recognizePrompt(screen, PromptAgent.Claude)
        val details = screen.lines.mapIndexed { row, text -> "$row: $text" }.joinToString("\n")
        assertNotNull("cursor=${screen.cursorRow} visible=${screen.cursorVisible}\n$details", prompt)
        assertEquals(listOf("Yes", "No"), prompt!!.choices.map { it.label })
        assertEquals(listOf("1", "2"), prompt.choices.map { it.text })
        assertTrue(prompt.context.contains("HATCH_REX_CLAUDE_POC"))
    }

    @Test fun nativeInspectionAllocatorRejectsTinyBudgetAndBoundsRetainedMemory() {
        assertTrue(runCatching { GhosttyTerminal(80, 24, memoryLimitBytes = 1) }.isFailure)
        GhosttyTerminal(80, 24, memoryLimitBytes = 4L * 1024 * 1024).use { terminal ->
            val bytes = ("x".repeat(79) + "\r\n").repeat(30_000).toByteArray()
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            terminal.write(buffer) { }
            assertTrue(terminal.budgetUsed() in 1..4L * 1024 * 1024)
        }
    }
}
