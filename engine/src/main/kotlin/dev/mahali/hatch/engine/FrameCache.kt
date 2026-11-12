package dev.mahali.hatch.engine

import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.BlockId
import java.io.File

public class FrameCache(private val dir: File) {
    private fun file(host: HostId, block: BlockId): File {
        val key = "${host.value}\u0000${block.value}".toByteArray()
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.frame")
    }

    /** Pane thread or any background thread. */
    public fun save(host: HostId, block: BlockId, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            val target = file(host, block)
            val temp = File(dir, target.name + ".tmp")
            temp.writeBytes(VERSION + bytes)
            temp.renameTo(target)
            trim()
        }
    }

    /** Never call on the main thread. */
    public fun load(host: HostId, block: BlockId): ByteArray? = runCatching {
        val bytes = file(host, block).takeIf { it.isFile }?.readBytes() ?: return null
        if (bytes.size <= VERSION.size || !bytes.copyOf(VERSION.size).contentEquals(VERSION)) return null
        bytes.copyOfRange(VERSION.size, bytes.size)
    }.getOrNull()

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".frame") } ?: return
        if (files.size <= MAX_FRAMES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FRAMES).forEach { it.delete() }
    }

    private companion object {
        const val MAX_FRAMES = 30
        /** Bumped whenever FrameBuffer's layout changes, so stale files are ignored. */
        val VERSION = "RPF1".toByteArray()
    }
}
