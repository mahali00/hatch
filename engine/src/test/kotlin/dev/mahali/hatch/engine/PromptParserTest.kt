package dev.mahali.hatch.engine

import dev.mahali.hatch.protocol.ForegroundProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PromptParserTest {
    private val codex = listOf(
        "Would you like to run the following command?", "", "  Environment: local", "",
        "  Reason: Allow this print-only command to test the approval buttons over the Rex terminal?", "",
        "  $ /usr/bin/python3 -c 'print(\"HATCH_REX_DIRECT_POC\")'", "",
        "› 1. Yes, proceed (y)",
        "  2. Yes, and don't ask again for commands that start with `/usr/bin/python3 -c",
        "     'print(\"HATCH_REX_DIRECT_POC\")'` (p)",
        "  3. No, and tell Codex what to do differently (esc)", "",
        "  Press enter to confirm or esc to cancel",
    )

    @Test fun `actual Codex menu keeps wrapped scope and advertised accelerators`() {
        val prompt = recognizePrompt(ActiveScreen(110, codex, 8), PromptAgent.Codex)
        assertNotNull(prompt)
        assertEquals(listOf("y", "p", ""), prompt!!.choices.map { it.text })
        assertEquals("escape", prompt.choices.last().key)
        assertEquals(3, prompt.choices.size)
        assertEquals(true, prompt.choices[1].label.contains("HATCH_REX_DIRECT_POC"))
    }

    @Test fun `clipped options or command do not yield actions`() {
        assertNull(recognizePrompt(ActiveScreen(110, codex.dropLast(1), 8), PromptAgent.Codex))
        val clipped = codex.map { it.replace("/usr/bin/python3", "/usr/bin/…") }
        assertNull(recognizePrompt(ActiveScreen(110, clipped, 8), PromptAgent.Codex))
    }

    @Test fun `historical menu above the live cursor is rejected`() {
        assertNull(recognizePrompt(ActiveScreen(110, codex, 0), PromptAgent.Codex))
        assertNull(recognizePrompt(ActiveScreen(110, codex + listOf("", "$ echo done"), codex.size + 1), PromptAgent.Codex))
    }

    @Test fun `unknown hotkey never becomes a guessed numeric answer`() {
        val changed = codex.map { it.replace("(y)", "(x)") }
        assertNull(recognizePrompt(ActiveScreen(110, changed, 8), PromptAgent.Codex))
    }

    @Test fun `modal with hidden conversation cursor requires one selected option`() {
        assertNotNull(recognizePrompt(ActiveScreen(110, codex, 0, cursorVisible = false), PromptAgent.Codex))
        val unselected = codex.map { it.replace('›', ' ') }
        assertNull(recognizePrompt(ActiveScreen(110, unselected, 0, cursorVisible = false), PromptAgent.Codex))
    }

    @Test fun `Claude permission menu includes the command and actual choices`() {
        val lines = listOf("Bash command", "──────────", "./gradlew check", "──────────", "",
            "Do you want to proceed?", "❯ 1. Yes", "  2. Yes, and don't ask again for this command", "  3. No", "Esc to cancel · Tab to amend")
        val prompt = recognizePrompt(ActiveScreen(80, lines, 6), PromptAgent.Claude)
        assertNotNull(prompt)
        assertEquals("Bash command\n./gradlew check", prompt!!.context)
        assertEquals(listOf("1", "2", "3"), prompt.choices.map { it.text })
        assertNull(recognizePrompt(ActiveScreen(80, lines.drop(2), 4), PromptAgent.Claude))
        assertNull(recognizePrompt(ActiveScreen(80, lines.map { if (it == "Bash command") "Edit file" else it }, 6), PromptAgent.Claude))
    }

    @Test fun `title and generic node process are insufficient agent identity`() {
        val node = ForegroundProcess(12, "node", "/usr/bin/node", "", "", "claude")
        assertNull(node.promptAgent())
        assertNull(node.copy(name = "opencode", path = "/opt/bin/opencode", argv0 = "opencode").promptAgent())
        assertEquals(PromptAgent.Claude, node.copy(script = "/opt/node_modules/@anthropic-ai/claude-code/cli.js").promptAgent())
        assertEquals(PromptAgent.Codex, node.copy(path = "/opt/bin/codex").promptAgent())
    }
}
