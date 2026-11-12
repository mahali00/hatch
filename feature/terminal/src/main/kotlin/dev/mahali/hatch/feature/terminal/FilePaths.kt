package dev.mahali.hatch.feature.terminal

import java.net.URLDecoder

// Not preceded by a word character, so src/a/b does not yield /a/b.
private val bare = Regex("""(?<![\w.~/-])~?/[^\s'"`<>|;,(){}\[\]]+""")
private val quoted = Regex("""(['"])(~?/[^'"]+)\1""")

private const val TRAILING = ".,:;!?"

internal fun filePathAt(text: String, index: Int): IntRange? {
    quoted.findAll(text).firstOrNull { index in it.range }?.let { return it.groups[2]!!.range }
    for (match in bare.findAll(text)) {
        var end = match.range.last
        while (end > match.range.first && text[end] in TRAILING) end--
        val range = match.range.first..end
        if (index in range && range.last > range.first) return range
        if (match.range.first > index) return null
    }
    return null
}

// True for paths from filePathAt or fileUrlPath: preview them, don't open them.
internal fun isFilePath(link: String): Boolean = link.startsWith("/") || link.startsWith("~/")

// Only file:// links with no host or localhost: the pane's machine may not be the host named.
internal fun fileUrlPath(link: String): String? {
    if (!link.startsWith("file://", ignoreCase = true)) return null
    val rest = link.substring(7)
    val path = when {
        rest.startsWith("/") -> rest
        rest.startsWith("localhost/", ignoreCase = true) -> rest.substring(9)
        else -> return null
    }
    return runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull()
}

// ponytail: guesses wraps from the cells, so a hard newline exactly at the edge joins too; ask the
// terminal for its wrap flags if that ever matters.
internal fun joinWrapped(rows: List<String>, row: Int): Pair<String, Int> {
    fun continues(a: String, b: String) = a.isNotEmpty() && !a.last().isWhitespace() && b.isNotEmpty() && !b.first().isWhitespace()
    var first = row
    while (first > 0 && continues(rows[first - 1], rows[first])) first--
    var last = row
    while (last < rows.lastIndex && continues(rows[last], rows[last + 1])) last++
    val start = (first until row).sumOf { rows[it].length }
    return (first..last).joinToString("") { rows[it] } to start
}
