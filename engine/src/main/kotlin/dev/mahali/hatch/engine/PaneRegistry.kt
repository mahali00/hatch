package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

internal data class PaneKey(val host: HostId, val block: BlockId)

/** Main thread only; [timers] must run on it too. */
internal class PaneRegistry(
    private val timers: CoroutineScope,
    private val frameCache: FrameCache?,
    private val keepWarmMillis: Long = KEEP_WARM_MILLIS,
    private val maxWarm: Int = MAX_WARM,
) {
    private class Entry(val pane: Pane, var users: Int)

    private val panes = mutableMapOf<PaneKey, Entry>()
    private var closed = false

    private val warm = LinkedHashMap<PaneKey, Job>()

    // Own pool, so a flooding pane never holds up connections or input on Dispatchers.Default.
    private val parsingPool = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2)) { task ->
        Thread(task, "pane-parse").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val parsing: CoroutineDispatcher get() = parsingPool

    /** Each caller gets its own idempotent release, even for a shared pane. */
    fun acquire(key: PaneKey, onWarm: (PaneKey) -> Unit = {}, create: () -> Pane?): PaneAcquisition? {
        if (closed) return null
        val pane = open(key, create) ?: return null
        return PaneAcquisition(pane) { release(pane)?.let(onWarm) }
    }

    private fun open(key: PaneKey, create: () -> Pane?): Pane? {
        panes[key]?.let { entry ->
            if (entry.users == 0) { warm.remove(key)?.cancel(); entry.pane.unpause() }
            entry.users++
            return entry.pane
        }
        val pane = create() ?: return null
        panes[key] = Entry(pane, 1)
        return pane
    }

    private fun release(pane: Pane): PaneKey? {
        val (key, entry) = panes.entries.firstOrNull { it.value.pane === pane } ?: return null
        if (entry.users == 0) return null
        if (--entry.users > 0) return null
        frameCache?.let { cache -> pane.captureFrame { cache.save(key.host, key.block, it) } }
        pane.pause()
        warm[key] = timers.launch {
            delay(keepWarmMillis)
            warm -= key
            if (panes[key]?.users == 0) panes.remove(key)?.pane?.close()
        }
        while (warm.size > maxWarm) closeNow(warm.keys.first())
        return key
    }

    fun shown(): List<Pane> = panes.values.filter { it.users > 0 }.map { it.pane }

    fun closeHost(host: HostId) { panes.keys.filter { it.host == host }.forEach(::closeNow) }

    fun close() {
        if (closed) return
        closed = true
        panes.keys.toList().forEach(::closeNow)
        parsingPool.close() // Lets queued work (freeing terminals) finish first.
    }

    private fun closeNow(key: PaneKey) {
        warm.remove(key)?.cancel()
        panes.remove(key)?.pane?.close()
    }
}

/** The server holds a dropped stream for 60 s; resume a little inside that. */
private const val KEEP_WARM_MILLIS = 55_000L

internal const val MAX_WARM = 12

/** Warm panes on a low-end phone, where twelve scrollbacks would crowd memory. */
internal const val LOW_END_MAX_WARM = 4
