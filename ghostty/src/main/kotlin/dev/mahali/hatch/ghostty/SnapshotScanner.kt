package dev.mahali.hatch.ghostty

import java.nio.ByteBuffer

/**
 * Envelope `GHOSTSNP` plus 2 bytes, then records of u16 tag, u32 length, u32 checksum.
 * The FINISH record (tag 6) ends the snapshot.
 */
public object SnapshotScanner {
    private val MAGIC = "GHOSTSNP".toByteArray()
    private const val ENVELOPE = 10
    private const val RECORD_HEADER = 10
    private const val FINISH = 6

    /** Needs 8 bytes to decide. */
    public fun notSnapshot(buffer: ByteBuffer, length: Int): Boolean =
        length >= MAGIC.size && MAGIC.indices.any { buffer.get(it) != MAGIC[it] }

    /** The offset just past FINISH, or -1 while the snapshot is still incomplete. */
    public fun end(buffer: ByteBuffer, length: Int): Int {
        var pos = ENVELOPE.toLong()
        while (pos + RECORD_HEADER <= length) {
            val p = pos.toInt()
            val next = pos + RECORD_HEADER + u32(buffer, p + 2)
            if (u16(buffer, p) == FINISH) return if (next <= length) next.toInt() else -1
            pos = next
        }
        return -1
    }

    private fun u16(b: ByteBuffer, i: Int) = (b.get(i).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8)
    private fun u32(b: ByteBuffer, i: Int): Long = (0..3).fold(0L) { acc, k -> acc or ((b.get(i + k).toLong() and 0xFF) shl (8 * k)) }
}
