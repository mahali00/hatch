package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.allBlocks

internal data class SessionMatch(val session: Session, val tabs: List<TabRowModel>)

internal fun HostState.search(query: String, preferGenerated: Boolean): List<SessionMatch> {
    val needle = query.trim()
    return sessions.mapNotNull { id ->
        val session = sessionsById[id] ?: return@mapNotNull null
        val rows = session.windows.mapNotNull { windowsById[it] }.map { it to tabRow(id, it, preferGenerated) }
        val nameMatches = session.label.contains(needle, ignoreCase = true)
        val kept = if (nameMatches) rows else rows.filter { (window, row) -> matches(window, row, needle) }
        if (nameMatches || kept.isNotEmpty()) SessionMatch(session, kept.map { it.second }) else null
    }
}

private fun HostState.matches(window: Window, row: TabRowModel, needle: String): Boolean {
    if (row.title.contains(needle, ignoreCase = true) || row.detail.contains(needle, ignoreCase = true)) return true
    return window.allBlocks().mapNotNull { blocksById[it] }.any { pane ->
        listOf(pane.title, pane.processName, pane.program.value, pane.workingDirectory)
            .any { it.contains(needle, ignoreCase = true) }
    }
}

internal fun matchRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return buildList {
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            add(at until at + needle.length)
            from = at + needle.length
        }
    }
}
