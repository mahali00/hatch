package dev.mahali.hatch.feature.terminal

import java.io.File
import java.util.UUID

// Close deletes the copy. A retained copy stays an hour so an external reader can finish.
internal class PreviewCopy private constructor(val file: File, private val directory: File) : AutoCloseable {
    private var released = false

    fun retain(nowMillis: Long = System.currentTimeMillis()) = synchronized(gate) {
        if (released) return@synchronized
        directory.setLastModified(nowMillis)
        active.remove(directory)
        released = true
    }

    override fun close() = synchronized(gate) {
        if (released) return@synchronized
        directory.deleteRecursively()
        active.remove(directory)
        released = true
    }

    companion object {
        private const val RETENTION_MILLIS = 60 * 60 * 1000L
        private val gate = Any()
        private val active = mutableSetOf<File>()

        fun create(root: File, name: String): PreviewCopy = synchronized(gate) {
            val directory = File(root, UUID.randomUUID().toString())
            check(directory.mkdirs()) { "Cannot create a preview copy" }
            active += directory
            PreviewCopy(File(directory, name.replace('/', '_').takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"), directory)
        }

        fun clean(root: File, nowMillis: Long = System.currentTimeMillis()) = synchronized(gate) {
            val stale = nowMillis - RETENTION_MILLIS
            root.listFiles()?.forEach { directory ->
                if (directory !in active && directory.lastModified() < stale) directory.deleteRecursively()
            }
        }
    }
}
