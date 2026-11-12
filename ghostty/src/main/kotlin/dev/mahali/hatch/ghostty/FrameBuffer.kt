package dev.mahali.hatch.ghostty

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Layout, in native-order ints: a header of [HEADER] ints, then four ints per cell (codepoint,
 * foreground, background, flags) row by row, then grapheme extras.
 */
public class FrameBuffer {
    internal var buffer: ByteBuffer = allocate(HEADER + 80 * 24 * 4 + EXTRAS)
        private set
    private var ints: IntBuffer = buffer.asIntBuffer()

    internal fun grow(bytes: Int) {
        buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        ints = buffer.asIntBuffer()
    }

    public val cols: Int get() = ints[0]
    public val rows: Int get() = ints[1]

    /** The cursor's viewport column, or -1 when it is not in the viewport. */
    public val cursorX: Int get() = ints[2]
    public val cursorY: Int get() = ints[3]
    public val cursorVisible: Boolean get() = ints[4] and 1 != 0

    /** 0 bar, 1 block, 2 underline, 3 hollow block. */
    public val cursorStyle: Int get() = ints[4] shr 2
    public val foreground: Int get() = ints[5]
    public val background: Int get() = ints[6]

    /** The cursor colour, or -1 to use the cell's foreground. */
    public val cursorColor: Int get() = ints[7]

    public val scrollTotal: Int get() = ints[8]
    public val scrollOffset: Int get() = ints[9]

    public val alternateScreen: Boolean get() = ints[12] and 1 != 0
    public val mouseTracking: Boolean get() = ints[12] and 2 != 0

    /** 1 when the frame also holds the row above, at cell indices `rows * cols` onwards; else 0. */
    public val rowsAbove: Int get() = ints[13]

    /** Pixels the viewport is drawn below its grid position; set by the producer, not libghostty. */
    public var shift: Int = 0

    public val alternateScroll: Boolean get() = ints[12] and 4 != 0

    /** A synchronized redraw is under way: this is the frame from before it, for at most a second. */
    public val held: Boolean get() = ints[12] and 16 != 0

    private fun cell(index: Int, field: Int) = ints[HEADER + index * 4 + field]

    private val extrasStart: Int get() = HEADER + cols * (rows + rowsAbove) * 4

    public fun toBytes(): ByteArray {
        val count = (extrasStart + ints[11].coerceAtLeast(0)) * 4
        val out = ByteArray(count)
        buffer.duplicate().apply { position(0); limit(count) }.get(out)
        return out
    }

    // Native byte order: bytes load only on the device that wrote them.
    public fun load(bytes: ByteArray) {
        if (buffer.capacity() < bytes.size) grow(bytes.size)
        buffer.duplicate().apply { position(0) }.put(bytes)
    }

    /** The cell's codepoint, 0 for empty, or a negative extras reference for a multi-codepoint grapheme. */
    public fun codepoint(index: Int): Int = cell(index, 0)
    public fun foreground(index: Int): Int = cell(index, 1)
    public fun background(index: Int): Int = cell(index, 2)
    public fun flags(index: Int): Int = cell(index, 3)

    public fun appendText(index: Int, out: StringBuilder) {
        val cp = codepoint(index)
        if (cp > 0) { out.appendCodePoint(cp); return }
        if (cp == 0) return
        val at = extrasStart + (-cp - 1)
        val count = ints[at]
        for (i in 1..count) out.appendCodePoint(ints[at + i])
    }

    public companion object {
        public const val HEADER: Int = 16
        public const val EXTRAS: Int = 4096

        public const val BOLD: Int = 1
        public const val ITALIC: Int = 2
        public const val FAINT: Int = 4
        public const val UNDERLINE: Int = 8
        public const val STRIKE: Int = 16
        public const val INVISIBLE: Int = 32
        public const val DEFAULT_BACKGROUND: Int = 128

        public const val WIDE: Int = 256
        public const val SPACER: Int = 512
        public const val MATCH: Int = 1024
        public const val MATCH_CURRENT: Int = 2048

        private fun allocate(ints: Int) = ByteBuffer.allocateDirect(ints * 4).order(ByteOrder.nativeOrder())
    }
}
