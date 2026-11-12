package dev.mahali.hatch.ghostty

import java.nio.ByteBuffer

/** A cell in screen coordinates: [y] counts from the first scrollback row, so it survives scrolling. */
public data class ScreenCell(val x: Int, val y: Int) : Comparable<ScreenCell> {
    override fun compareTo(other: ScreenCell): Int = compareValuesBy(this, other, { it.y }, { it.x })
}

public data class SearchInfo(val total: Int, val selected: Int?, val complete: Boolean)

public enum class CursorShape(internal val native: Int) { Bar(0), Block(1), Underline(2) }

/**
 * Not thread-safe by design: the pane actor that creates it is its only user, so no call takes
 * a lock.
 */
public class GhosttyTerminal(cols: Int = 80, rows: Int = 24, memoryLimitBytes: Long = 0) : AutoCloseable {
    private var handle: Long = if (memoryLimitBytes > 0) nativeNewBounded(cols, rows, memoryLimitBytes) else nativeNew(cols, rows)

    init {
        check(handle != 0L) { "ghostty_terminal_new failed for ${cols}x$rows" }
    }

    /** Returns false if the snapshot does not decode. */
    public fun restore(buffer: ByteBuffer, offset: Int, length: Int): Boolean {
        require(buffer.isDirect)
        return nativeRestore(live(), buffer, offset, length) == 0
    }

    /** Calls [onRexMessage] with each Rex APC payload (`rex1;<nonce>;<json>`) where it occurs. */
    public inline fun write(buffer: ByteBuffer, onRexMessage: (String) -> Unit) {
        require(buffer.isDirect)
        while (buffer.hasRemaining()) {
            val result = writeChunk(buffer, buffer.position(), buffer.remaining())
            val consumed = result and Int.MAX_VALUE
            buffer.position(buffer.position() + consumed)
            if (result < 0) takeRexMessage()?.let(onRexMessage)
            if (consumed == 0) break
        }
    }

    @PublishedApi internal fun writeChunk(buffer: ByteBuffer, offset: Int, length: Int): Int = nativeWrite(live(), buffer, offset, length)
    @PublishedApi internal fun takeRexMessage(): String? = nativeTakeApc(live())

    public fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int): Unit = nativeResize(live(), cols, rows, cellWidth, cellHeight)

    /** Colours as 0xRRGGBB, or -1 for the built-in one. [palette] holds (index, 0xRRGGBB) pairs. */
    public fun setColors(foreground: Int, background: Int, cursor: Int, palette: IntArray): Unit =
        nativeSetColors(live(), foreground, background, cursor, palette)

    public fun setCursorDefaults(shape: CursorShape, blink: Boolean): Unit = nativeCursorDefaults(live(), shape.native, blink)

    /** Negative scrolls up into history. Returns the viewport's first row among all rows afterwards. */
    public fun scroll(lines: Int): Int = if (lines == 0) viewportTop() else nativeScroll(live(), lines)

    public fun viewportTop(): Int = nativeViewportTop(live())
    public fun scrollToBottom() { nativeScroll(live(), 0) }

    /** A bounded inspection terminal must discard any result after allocator exhaustion. */
    public fun budgetFailed(): Boolean = nativeBudgetFailed(live())
    public fun budgetUsed(): Long = nativeBudgetUsed(live())

    public fun snapshot(frame: FrameBuffer, rowAbove: Boolean = false) {
        var need = nativeFrame(live(), frame.buffer, FrameBuffer.EXTRAS, rowAbove)
        if (need > 0) {
            frame.grow(need)
            need = nativeFrame(live(), frame.buffer, FrameBuffer.EXTRAS, rowAbove)
        }
        check(need == 0) { "ghostty render state failed" }
    }

    public fun linkAt(col: Int, row: Int): String? = nativeLinkAt(live(), col, row)

    public fun selectionText(a: ScreenCell, b: ScreenCell): String? {
        val (start, end) = if (a <= b) a to b else b to a
        return nativeSelectionText(live(), start.x, start.y, end.x, end.y)
    }

    public fun selectAll(): Pair<ScreenCell, ScreenCell>? {
        val v = IntArray(4)
        return if (nativeSelectAll(live(), v)) ScreenCell(v[0], v[1]) to ScreenCell(v[2], v[3]) else null
    }

    /** Advances a bounded step per frame, so a long scrollback never blocks one. */
    public fun search(needle: String?): Unit = nativeSearchSet(live(), needle)

    public fun searchSelect(older: Boolean): Boolean = nativeSearchSelect(live(), older)

    /** Selected is null when none is; 0 is the newest match. */
    public fun searchInfo(): SearchInfo {
        val v = IntArray(3)
        nativeSearchInfo(live(), v)
        return SearchInfo(v[0], v[1].takeIf { it >= 0 }, v[2] == 1)
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private fun live(): Long = handle.also { check(it != 0L) { "GhosttyTerminal is closed" } }

    private companion object {
        init {
            System.loadLibrary("hatchghostty")
        }

        @JvmStatic external fun nativeNew(cols: Int, rows: Int): Long
        @JvmStatic external fun nativeNewBounded(cols: Int, rows: Int, limit: Long): Long
        @JvmStatic external fun nativeBudgetFailed(handle: Long): Boolean
        @JvmStatic external fun nativeBudgetUsed(handle: Long): Long
        @JvmStatic external fun nativeFree(handle: Long)
        @JvmStatic external fun nativeRestore(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
        @JvmStatic external fun nativeWrite(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
        @JvmStatic external fun nativeTakeApc(handle: Long): String?
        @JvmStatic external fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidth: Int, cellHeight: Int)
        @JvmStatic external fun nativeSetColors(handle: Long, fg: Int, bg: Int, cursor: Int, palette: IntArray)
        @JvmStatic external fun nativeCursorDefaults(handle: Long, style: Int, blink: Boolean)
        @JvmStatic external fun nativeScroll(handle: Long, delta: Int): Int
        @JvmStatic external fun nativeViewportTop(handle: Long): Int
        @JvmStatic external fun nativeFrame(handle: Long, buffer: ByteBuffer, extrasCap: Int, rowAbove: Boolean): Int
        @JvmStatic external fun nativeLinkAt(handle: Long, col: Int, row: Int): String?
        @JvmStatic external fun nativeSelectionText(handle: Long, x1: Int, y1: Int, x2: Int, y2: Int): String?
        @JvmStatic external fun nativeSelectAll(handle: Long, out: IntArray): Boolean
        @JvmStatic external fun nativeSearchSet(handle: Long, needle: String?)
        @JvmStatic external fun nativeSearchSelect(handle: Long, older: Boolean): Boolean
        @JvmStatic external fun nativeSearchInfo(handle: Long, out: IntArray)
    }
}
