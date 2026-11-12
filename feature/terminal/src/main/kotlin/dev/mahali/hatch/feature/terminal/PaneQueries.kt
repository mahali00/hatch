package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.engine.Pane
import dev.mahali.hatch.engine.RowText
import dev.mahali.hatch.ghostty.ScreenCell
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Questions for the pane thread, as suspending calls. The pane answers on its own thread; the
// caller resumes on its dispatcher, so the main thread never waits on the pane.

internal suspend fun Pane.awaitRow(row: Int): RowText? = suspendCancellableCoroutine { c -> rowText(row) { c.resume(it) } }

internal suspend fun Pane.awaitLink(col: Int, row: Int): String? = suspendCancellableCoroutine { c -> linkAt(col, row) { c.resume(it) } }

internal suspend fun Pane.awaitText(a: ScreenCell, b: ScreenCell): String? =
    suspendCancellableCoroutine { c -> selectionText(a, b) { c.resume(it) } }

internal suspend fun Pane.awaitAll(): Pair<ScreenCell, ScreenCell>? = suspendCancellableCoroutine { c -> selectAll { c.resume(it) } }

// A wide character's second cell maps to the character's index.
internal fun RowText.indexOf(col: Int): Int = columns.indexOfLast { it <= col }

// Returns an OSC 8 link, http(s) URL or file path; a file:// link comes back as its path.
internal suspend fun Pane.linkUnder(col: Int, row: Int): String? {
    awaitLink(col, row)?.let { return fileUrlPath(it) ?: it }
    val line = awaitRow(row) ?: return null
    val index = line.indexOf(col).takeIf { it >= 0 } ?: return null
    // A long path or URL wraps; read up to three rows either side to rejoin it.
    val rows = (row - 3..row + 3).map { if (it == row) line.text else awaitRow(it)?.text.orEmpty() }
    val (text, start) = joinWrapped(rows, 3)
    val at = start + index
    return (urlAt(text, at) ?: filePathAt(text, at))?.let { text.substring(it) }
}

internal suspend fun Pane.wordUnder(col: Int, row: Int): Pair<ScreenCell, ScreenCell>? {
    val line = awaitRow(row) ?: return null
    val index = line.indexOf(col).takeIf { it >= 0 } ?: return null
    val word = wordAt(line.text, index).takeIf { !it.isEmpty() } ?: return null
    return ScreenCell(line.columns[word.first], line.screenRow) to ScreenCell(line.columns[word.last], line.screenRow)
}
