package dev.mahali.hatch.engine

import dev.mahali.hatch.ghostty.FrameExchange
import dev.mahali.hatch.core.PanePriority

/** Main thread only. */
public class PaneAcquisition internal constructor(
    public val pane: Pane,
    private val release: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private var attachment: PaneAttachment? = null
    public val isActive: Boolean get() = !closed && pane.isActive

    /** Waits for the previous visual consumer to detach before connecting. */
    public fun attach(
        connect: (FrameExchange) -> Unit,
        disconnect: () -> Unit,
        onFrame: () -> Unit,
    ): PaneAttachment {
        check(isActive) { "Terminal acquisition is closed" }
        return pane.attach(connect, disconnect, onFrame).also { attachment = it }
    }

    override fun close() {
        if (closed) return
        closed = true
        attachment?.close()
        attachment = null
        release()
    }
}

/** Exclusive visual ownership. Disconnect must return only after the consumer has stopped. */
public class PaneAttachment internal constructor(detach: () -> Unit, updatePriority: (PanePriority) -> Unit) : AutoCloseable {
    private var detach: (() -> Unit)? = detach
    private var updatePriority: ((PanePriority) -> Unit)? = updatePriority
    public val isActive: Boolean get() = detach != null

    public fun setPriority(priority: PanePriority) { updatePriority?.invoke(priority) }

    override fun close() {
        val action = detach ?: return
        detach = null
        updatePriority = null
        action()
    }
}

/** Serializes callback delivery with detach, so no view callback survives a completed close. */
internal class PaneAttachments(private val setPriority: (PanePriority) -> Unit = {}) {
    private class Consumer(val handle: PaneAttachment, val onFrame: () -> Unit)
    private var consumer: Consumer? = null

    @Synchronized fun attach(connect: () -> Unit, disconnect: () -> Unit, onFrame: () -> Unit): PaneAttachment {
        consumer?.handle?.close()
        lateinit var handle: PaneAttachment
        handle = PaneAttachment(detach = {
            synchronized(this) {
                if (consumer?.handle === handle) {
                    consumer = null
                    setPriority(PanePriority.Background)
                    disconnect()
                }
            }
        }, updatePriority = { priority ->
            synchronized(this) { if (consumer?.handle === handle) setPriority(priority) }
        })
        try { connect() } catch (failure: Throwable) { disconnect(); throw failure }
        consumer = Consumer(handle, onFrame)
        setPriority(PanePriority.Visible)
        return handle
    }

    @Synchronized fun notifyFrame() { consumer?.onFrame?.invoke() }
    @Synchronized fun close() { consumer?.handle?.close() }
}
