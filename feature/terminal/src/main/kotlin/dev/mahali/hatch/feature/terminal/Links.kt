package dev.mahali.hatch.feature.terminal

private val url = Regex("""https?://[^\s<>"'`]+""")

private const val TRAILING = ".,;:!?'\""

internal fun urlAt(text: String, index: Int): IntRange? {
    for (match in url.findAll(text)) {
        val range = trimUrl(text, match.range)
        if (index in range) return range
        if (match.range.first > index) return null
    }
    return null
}

private fun trimUrl(text: String, range: IntRange): IntRange {
    var end = range.last
    while (end > range.first) {
        val c = text[end]
        val part = text.substring(range.first, end + 1)
        val unbalanced = (c == ')' && part.count { it == ')' } > part.count { it == '(' }) ||
            (c == ']' && part.count { it == ']' } > part.count { it == '[' })
        if (c in TRAILING || unbalanced) end-- else break
    }
    return range.first..end
}
