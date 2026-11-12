package dev.mahali.hatch.ghostty

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class GhosttyTerminalTest {
    private val term = GhosttyTerminal(20, 5)
    private val frame = FrameBuffer()

    @After fun close() = term.close()

    private fun feed(text: String, onRex: (String) -> Unit = {}) {
        val bytes = text.toByteArray()
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buffer.flip()
        term.write(buffer, onRex)
    }

    private fun row(y: Int): String = buildString {
        for (x in 0 until frame.cols) frame.appendText(y * frame.cols + x, this)
    }.trimEnd()

    @Test fun textAndColoursReachTheFrame() {
        feed("hello \u001b[31mred\u001b[0m\r\nline two")
        term.snapshot(frame)
        assertEquals(20, frame.cols)
        assertEquals(5, frame.rows)
        assertEquals("hello red", row(0))
        assertEquals("line two", row(1))
        val red = frame.foreground(6)
        assertTrue("red is red: ${Integer.toHexString(red)}", (red shr 16) and 0xFF > 0x80)
        assertEquals(1, frame.cursorY)
    }

    @Test fun aSmoothScrollFrameHoldsTheRowAboveAndLeavesTheViewportWhereItWas() {
        feed((0 until 12).joinToString("\r\n") { "line $it" })
        assertEquals(7, term.viewportTop())
        term.snapshot(frame, rowAbove = true)
        assertEquals(1, frame.rowsAbove)
        assertEquals("line 6", row(frame.rows))
        assertEquals("line 7", row(0))
        assertEquals(7, term.viewportTop())
        feed("\r\nline 12")
        assertEquals(8, term.viewportTop())
        term.snapshot(frame, rowAbove = true)
        assertEquals("line 7", row(frame.rows))
        assertEquals(4, frame.cursorY)
        term.snapshot(frame)
        assertEquals(0, frame.rowsAbove)

        assertEquals(6, term.scroll(-2))
        assertEquals(0, term.scroll(-100))
        term.snapshot(frame, rowAbove = true)
        assertEquals(0, frame.rowsAbove)
        assertEquals("line 0", row(0))
    }

    @Test fun aSynchronizedRedrawShowsOnlyOnceItEnds() {
        feed("before")
        feed("\u001b[?2026h\u001b[2J\u001b[Hafter")
        term.snapshot(frame)
        assertTrue(frame.held)
        assertEquals("before", row(0))
        feed("\u001b[?2026l")
        term.snapshot(frame)
        assertFalse(frame.held)
        assertEquals("after", row(0))

        feed("\u001b[?2026h more")
        term.scrollToBottom()
        term.snapshot(frame)
        assertFalse(frame.held)
        assertEquals("after more", row(0))
    }

    @Test fun backgroundsInverseAndGraphemesReachTheFrame() {
        feed("\u001b[44mb\u001b[7mi\u001b[0m\u001b[41m\u001b[K\u001b[0m\r\né")
        term.snapshot(frame)
        fun blue(c: Int) = (c and 0xFF) > (c shr 16 and 0xFF)
        assertEquals(frame.foreground, frame.foreground(0))
        assertTrue(blue(frame.background(0)))
        assertEquals(0, frame.flags(0) and FrameBuffer.DEFAULT_BACKGROUND)
        assertTrue(blue(frame.foreground(1)))
        assertEquals(frame.foreground, frame.background(1))
        assertEquals(0, frame.codepoint(5))
        assertEquals(frame.foreground, frame.foreground(5))
        assertFalse(blue(frame.background(5)))
        assertEquals(0, frame.flags(5) and FrameBuffer.DEFAULT_BACKGROUND)
        assertTrue(frame.flags(frame.cols) and FrameBuffer.DEFAULT_BACKGROUND != 0)
        assertTrue(frame.codepoint(frame.cols) < 0)
        assertEquals("é", row(1))
    }

    @Test fun rexMessagesArriveInOrderWithTheBytes() {
        val seen = mutableListOf<String>()
        feed("ab\u001b_rex1;7;{\"type\":\"resize\"}\u001b\\cd") { msg ->
            term.snapshot(frame)
            seen += msg + "@" + row(0)
        }
        assertEquals(listOf("rex1;7;{\"type\":\"resize\"}@ab"), seen)
        term.snapshot(frame)
        assertEquals("abcd", row(0))
    }

    @Test fun searchFindsMatchesInScrollbackAndMarksTheVisibleOnes() {
        feed("needle one\r\nb\r\nc\r\nd\r\ne\r\nf\r\nthe NEEDLE two")
        term.search("needle")
        repeat(20) { term.snapshot(frame) }
        val info = term.searchInfo()
        assertEquals(2, info.total)
        assertTrue(info.complete)
        val y = (0 until frame.rows).first { row(it).contains("NEEDLE") }
        val x = row(y).indexOf("NEEDLE")
        for (i in x until x + 6) assertTrue("cell $i is a match", frame.flags(y * frame.cols + i) and FrameBuffer.MATCH != 0)
        assertEquals(0, frame.flags(y * frame.cols) and FrameBuffer.MATCH)

        assertTrue(term.searchSelect(older = true))
        assertTrue(term.searchSelect(older = true))
        term.snapshot(frame)
        assertEquals(1, term.searchInfo().selected)
        val top = (0 until frame.rows).first { row(it).startsWith("needle one") }
        assertTrue(frame.flags(top * frame.cols) and FrameBuffer.MATCH_CURRENT != 0)

        term.search(null)
        term.snapshot(frame)
        assertEquals(0, term.searchInfo().total)
        assertEquals(0, frame.flags(top * frame.cols) and FrameBuffer.MATCH)
    }

    @Test fun otherApcSequencesAreIgnored() {
        val seen = mutableListOf<String>()
        feed("x\u001b_other;1\u001b\\y") { seen += it }
        assertTrue(seen.isEmpty())
    }

    @Test fun wideCharactersTakeTwoCells() {
        feed("漢a")
        term.snapshot(frame)
        assertTrue(frame.flags(0) and FrameBuffer.WIDE != 0)
        assertTrue(frame.flags(1) and FrameBuffer.SPACER != 0)
        assertEquals('a'.code, frame.codepoint(2))
    }

    @Test fun selectionCopiesUnwrappedText() {
        feed("first line\r\nsecond")
        assertEquals("first line\nsecond", term.selectionText(ScreenCell(0, 0), ScreenCell(19, 1)))
        val all = term.selectAll()
        assertFalse(all == null)
    }

    @Test fun resizeAndAlternateScreen() {
        term.resize(40, 10, 9, 18)
        feed("\u001b[?1049h")
        term.snapshot(frame)
        assertEquals(40, frame.cols)
        assertTrue(frame.alternateScreen)
    }

    @Test fun aNonSnapshotIsRecognisedQuickly() {
        val b = ByteBuffer.allocateDirect(16).put("plain vt output".toByteArray())
        assertTrue(SnapshotScanner.notSnapshot(b, 15))
    }
}
