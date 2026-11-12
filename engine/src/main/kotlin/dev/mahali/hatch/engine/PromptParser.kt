package dev.mahali.hatch.engine

import dev.mahali.hatch.protocol.ForegroundProcess
import dev.mahali.hatch.protocol.PromptScreenExport
import dev.mahali.hatch.ghostty.FrameBuffer
import dev.mahali.hatch.ghostty.GhosttyTerminal
import java.nio.ByteBuffer
import java.security.MessageDigest

internal enum class PromptAgent { Claude, Codex }
internal data class ActiveScreen(val columns: Int, val lines: List<String>, val cursorRow: Int, val cursorVisible: Boolean = true)
internal data class ParsedChoice(val label: String, val number: Int, val key: String = "unidentified", val text: String = number.toString())
internal data class ParsedPrompt(val agent: PromptAgent, val question: String, val context: String, val choices: List<ParsedChoice>) {
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(
        (listOf(agent.name, question, context) + choices.map { "${it.number}:${it.label}" }).joinToString("\u0000").toByteArray()
    ).joinToString("") { "%02x".format(it) }
}

internal fun ForegroundProcess.promptAgent(): PromptAgent? {
    val binaries = listOf(path, invokedPath).filter { it.startsWith('/') }.map { it.substringAfterLast('/') }
    return when {
        binaries.any { it == "claude" || it == "claude-code" } -> PromptAgent.Claude
        binaries.any { it == "codex" || it.startsWith("codex-") } -> PromptAgent.Codex
        script.contains("/@anthropic-ai/claude-code/") && script.endsWith("/cli.js") -> PromptAgent.Claude
        script.contains("/@openai/codex/") && script.endsWith("/codex.js") -> PromptAgent.Codex
        else -> null
    }
}

internal fun decodePromptScreen(export: PromptScreenExport): ActiveScreen {
    val identity = export.identity
    return GhosttyTerminal(identity.columns, identity.rows, memoryLimitBytes = 4L * 1024 * 1024).use { terminal ->
        val bytes = export.vt.toByteArray()
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
        terminal.write(buffer) { }
        check(!terminal.budgetFailed()) { "Screen decode budget exceeded" }
        terminal.scrollToBottom()
        val frame = FrameBuffer()
        terminal.snapshot(frame)
        check(!terminal.budgetFailed()) { "Screen decode budget exceeded" }
        check(frame.cols == identity.columns && frame.rows == identity.rows) { "Screen dimensions changed" }
        check(frame.alternateScreen == identity.alternate) { "Screen mode changed" }
        val lines = (0 until frame.rows).map { row ->
            buildString {
                for (column in 0 until frame.cols) {
                    val index = row * frame.cols + column
                    if (frame.flags(index) and FrameBuffer.SPACER != 0) continue
                    if (frame.flags(index) and FrameBuffer.INVISIBLE != 0 || frame.codepoint(index) == 0) append(' ')
                    else frame.appendText(index, this)
                }
            }.trimEnd()
        }
        ActiveScreen(frame.cols, lines, frame.cursorY, frame.cursorVisible)
    }
}

/** Narrow on purpose: similar text in scrollback never supplies keys. */
internal fun recognizePrompt(screen: ActiveScreen, agent: PromptAgent): ParsedPrompt? {
    val lines = screen.lines
    if (screen.cursorRow !in lines.indices || screen.columns < 30) return null
    val questionAt = lines.indexOfLast { line -> when (agent) {
        PromptAgent.Claude -> line.trim() == "Do you want to proceed?"
        PromptAgent.Codex -> line.trim() == "Would you like to run the following command?"
    } }
    if (questionAt < 0) return null
    val option = Regex("""^\s*(?:[❯›>]\s*)?(\d)[.)]\s+(\S.*)$""")
    val choices = mutableListOf<ParsedChoice>()
    val firstOption = (questionAt + 1 until lines.size).firstOrNull { option.matches(lines[it]) } ?: return null
    if (agent == PromptAgent.Claude && lines.subList(questionAt + 1, firstOption).any { it.isNotBlank() }) return null
    var tail = firstOption
    while (tail < lines.size) {
        val match = option.matchEntire(lines[tail]) ?: break
        val label = StringBuilder(match.groupValues[2].trim())
        tail++
        while (tail < lines.size && lines[tail].startsWith("     ") && lines[tail].isNotBlank() && !option.matches(lines[tail])) {
            label.append(' ').append(lines[tail].trim())
            tail++
        }
        choices += ParsedChoice(label.toString(), match.groupValues[1].toInt())
    }
    if (choices.size !in 2..9 || choices.map { it.number } != (1..choices.size).toList()) return null
    val footer = lines.drop(tail).filter { it.isNotBlank() }.map { it.trim() }
    val footerValid = footer.isNotEmpty() && footer.size <= 3 && footer.all { line ->
        when (agent) {
            PromptAgent.Claude -> line.contains("Esc to cancel", ignoreCase = true) || line.contains("Tab to amend", ignoreCase = true)
            PromptAgent.Codex -> line.contains("enter", ignoreCase = true) && line.contains("esc", ignoreCase = true)
        }
    }
    if (!footerValid) return null
    if (screen.cursorVisible) {
        if (screen.cursorRow < firstOption || screen.cursorRow > tail + footer.size + 1) return null
    } else {
        // Modal TUIs hide the cursor in the conversation below; their menu has one selection and a footer.
        val marked = lines.subList(firstOption, tail).count { it.trimStart().firstOrNull() in listOf('❯', '›', '>') }
        if (marked != 1) return null
    }
    val context: String = when (agent) {
        PromptAgent.Claude -> {
            val start = lines.subList(0, questionAt).indexOfLast { it.trim() == "Bash command" }
            if (start < 0) return null
            val relevant = lines.subList(start, questionAt).filter { line -> line.isNotBlank() && !line.trim().all { it in "─━╌╍- " } }
            if (relevant.size < 2) return null
            relevant.joinToString("\n") { it.trim() }
        }
        PromptAgent.Codex -> {
            val relevant = lines.subList(questionAt + 1, firstOption).filter { it.isNotBlank() }
            if (relevant.none { it.trim().startsWith("$ ") }) return null
            relevant.joinToString("\n") { it.trim() }
        }
    }
    if (context.any { it == '…' } || context.contains("...") || choices.any { it.label.contains('…') }) return null
    val allowed = when (agent) {
        PromptAgent.Claude -> choices.all { it.label == "Yes" || it.label == "No" || it.label.startsWith("Yes, ") }
        PromptAgent.Codex -> choices.all { it.label.startsWith("Yes,") || it.label.startsWith("No,") }
    }
    if (!allowed) return null
    val mapped = if (agent == PromptAgent.Codex) choices.map { choice ->
        when {
            choice.label.endsWith("(y)") -> choice.copy(text = "y")
            choice.label.endsWith("(p)") -> choice.copy(text = "p")
            choice.label.endsWith("(esc)") -> choice.copy(key = "escape", text = "")
            else -> return null
        }
    } else choices.toList()
    return ParsedPrompt(agent, lines[questionAt].trim(), context, mapped)
}
