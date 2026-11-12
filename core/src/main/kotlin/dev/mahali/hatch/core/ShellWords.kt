package dev.mahali.hatch.core

/** The server runs a host's startup command as an argument list, so split it like a POSIX shell. */
public fun shellWords(line: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var inWord = false
    var quote: Char? = null
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quote == '\'' -> if (c == '\'') quote = null else word.append(c)
            quote == '"' -> when {
                c == '"' -> quote = null
                c == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`" -> word.append(line[++i])
                else -> word.append(c)
            }
            c == '\'' || c == '"' -> { quote = c; inWord = true }
            c == '\\' && i + 1 < line.length -> { word.append(line[++i]); inWord = true }
            c.isWhitespace() -> if (inWord) { words += word.toString(); word.clear(); inWord = false }
            else -> { word.append(c); inWord = true }
        }
        i++
    }
    if (inWord) words += word.toString()
    return words
}
