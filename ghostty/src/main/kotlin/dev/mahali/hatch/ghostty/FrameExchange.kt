package dev.mahali.hatch.ghostty

import java.util.concurrent.atomic.AtomicReference

/** Lock-free triple buffer: one producer (pane thread), one consumer (render thread). */
public class FrameExchange {
    private class Slot(val frame: FrameBuffer, val fresh: Boolean)

    private var back = FrameBuffer()
    private var front = FrameBuffer()
    private val ready = AtomicReference(Slot(FrameBuffer(), fresh = false))

    /** Producer only. */
    public fun backFrame(): FrameBuffer = back

    /** Producer only. */
    public fun publish() {
        back = ready.getAndSet(Slot(back, fresh = true)).frame
    }

    /** Consumer only. Null before the first frame. */
    public fun take(): FrameBuffer? {
        if (ready.get().fresh) front = ready.getAndSet(Slot(front, fresh = false)).frame
        return front.takeIf { it.cols > 0 }
    }
}
