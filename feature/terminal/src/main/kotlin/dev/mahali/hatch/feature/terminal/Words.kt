package dev.mahali.hatch.feature.terminal

private const val WORD_EXTRAS = "_-./~:@%+=?&#"

// A press on a space selects just that character, as terminals do.
internal fun wordAt(text: String, index: Int): IntRange {
    if (index !in text.indices) return IntRange.EMPTY
    if (!isWordChar(text[index])) return index..index
    var start = index
    var end = index
    while (start > 0 && isWordChar(text[start - 1])) start--
    while (end < text.lastIndex && isWordChar(text[end + 1])) end++
    return start..end
}

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c in WORD_EXTRAS || c.isSurrogate()
