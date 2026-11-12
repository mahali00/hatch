package dev.mahali.hatch.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilePathsTest {
    private fun at(text: String, tap: String) = filePathAt(text, text.indexOf(tap))?.let { text.substring(it) }

    @Test fun absoluteAndHomePaths() {
        assertEquals("/Users/me/a.gif", at("saved /Users/me/a.gif.", "a.gif"))
        assertEquals("~/Downloads/clip.mp4", at("see ~/Downloads/clip.mp4, done", "clip"))
    }

    @Test fun relativeAndUrlPartsAreNotPaths() {
        assertNull(at("src/main/App.kt", "main"))
        assertNull(at("no path here", "path"))
    }

    @Test fun quotedPathsKeepSpaces() {
        assertEquals("/Users/me/My Movies/a b.mov", at("wrote '/Users/me/My Movies/a b.mov' ok", "Movies"))
        assertEquals("~/x y.png", at("open \"~/x y.png\"", "y.png"))
    }

    @Test fun localFileUrlsBecomePaths() {
        assertEquals("/Users/me/a b.gif", fileUrlPath("file:///Users/me/a%20b.gif"))
        assertEquals("/tmp/c+d.txt", fileUrlPath("file://localhost/tmp/c+d.txt"))
        assertNull(fileUrlPath("file://otherhost/etc/passwd"))
        assertNull(fileUrlPath("https://example.com/a.gif"))
    }

    @Test fun wrappedRowsRejoin() {
        val rows = listOf("$ ls      ", "/Users/me/", "Developer/", "a.gif     ", "$         ")
        val (text, start) = joinWrapped(rows, 2)
        assertEquals("/Users/me/Developer/a.gif", text.substring(filePathAt(text, start + 3)!!))
    }

    @Test fun longPreviewLinesSplitWithoutBreakingAPair() {
        assertEquals(listOf(""), displayLines(""))
        assertEquals(listOf("abcd", "ef"), displayLines("abcdef", 4))
        val emoji = "abc\uD83D\uDE00de"
        assertEquals(listOf("abc", "\uD83D\uDE00de"), displayLines(emoji, 4))
        assertEquals(emoji, displayLines(emoji, 4).joinToString(""))
    }
}
