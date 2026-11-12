package dev.mahali.hatch.engine

import android.os.Trace
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Cell
import dev.mahali.hatch.core.GridSize
import dev.mahali.hatch.core.Modes
import dev.mahali.hatch.core.PaneGeometry
import dev.mahali.hatch.core.PanePriority
import dev.mahali.hatch.core.Scroll
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.ghostty.CursorShape
import dev.mahali.hatch.ghostty.FrameBuffer
import dev.mahali.hatch.ghostty.FrameExchange
import dev.mahali.hatch.ghostty.GhosttyTerminal
import dev.mahali.hatch.ghostty.ScreenCell
import dev.mahali.hatch.ghostty.SearchInfo
import dev.mahali.hatch.ghostty.SnapshotScanner
import dev.mahali.hatch.protocol.DataSink
import dev.mahali.hatch.protocol.DataStream
import dev.mahali.hatch.protocol.InBandMessage
import dev.mahali.hatch.protocol.decodeInBand
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.time.Duration.Companion.nanoseconds

public data class PaneStatus(
    val stream: DataStream.State = DataStream.State.Opening,
    val restored: Boolean = false,
    val cached: Boolean = false,
    val exited: Boolean = false,
    val cols: Int = 0,
    val rows: Int = 0,
    val failures: Int = 0,
    val exitCode: Int? = null,
    val exitError: String = "",
)

public class RowText(public val text: String, public val columns: IntArray, public val screenRow: Int)

/**
 * Producers [offer] from any thread; [schedule] runs at most one drain at a time, which hands items to
 * [handle] in arrival order. One task per flood instead of one per message: a host sending a message per
 * output line made 87,000 coroutines in 6 s, over half the parse threads' CPU.
 */
internal class Drain<T : Any>(
    private val schedule: (() -> Unit) -> Unit,
    private val handle: (T) -> Unit,
    private val weigh: (T) -> Int = { 1 },
    /** Weight handled per drain, so frames and user actions on the same lane run between drains. */
    private val budget: Int = 256 * 1024,
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val pending = AtomicBoolean(false)

    fun offer(item: T) {
        queue.add(item)
        if (pending.compareAndSet(false, true)) schedule(::run)
    }

    fun clear() = queue.clear()

    private fun run() {
        var spent = 0
        while (spent < budget) {
            val item = queue.poll() ?: break
            spent += weigh(item)
            try { handle(item) } catch (e: Throwable) { e.printStackTrace() }
        }
        // Over budget: stay pending and queue behind whatever else is on the lane.
        if (spent >= budget) return schedule(::run)
        // Clear before the re-check: an offer whose CAS failed saw the flag set, and added its item before
        // that, so the check below sees it; an offer after the clear schedules itself.
        pending.set(false)
        if (!queue.isEmpty() && pending.compareAndSet(false, true)) schedule(::run)
    }
}

private class Begin(val nonce: Long)

/** Only the pane thread touches the terminal; everything else posts to it. */
public class Pane internal constructor(
    public val session: SessionId,
    public val block: BlockId,
    streamFactory: (DataSink) -> DataStream,
    parsing: CoroutineDispatcher,
    /** The pane's saved frame from an earlier visit, read on the pane thread, or null. */
    private val savedFrame: () -> ByteArray? = { null },
) : AutoCloseable {
    // Serial lane on the shared parse pool: this lane is the pane thread.
    private val lane = CoroutineScope(
        SupervisorJob() + parsing.limitedParallelism(1) + CoroutineExceptionHandler { _, e -> e.printStackTrace() },
    )
    @Volatile private var closed = false
    public val isActive: Boolean get() = !closed

    // Pane thread only.
    private var term: GhosttyTerminal? = null
    private var localColors: TerminalColors? = null
    private var nonce = 0L
    private var snapshot: ByteBuffer? = null
    private var snapshotLength = 0
    private var input: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder())
    private var frameScheduled = false
    private var holdWake = false
    private var frameDue = 0L
    private var cursor = CursorShape.Block to true
    private var shift = 0f

    private val frames: FrameExchange = FrameExchange()
    private val attachments = PaneAttachments { priority = it }

    internal fun attach(connect: (FrameExchange) -> Unit, disconnect: () -> Unit, onFrame: () -> Unit): PaneAttachment {
        check(!closed) { "Pane is closed" }
        return attachments.attach({ connect(frames) }, disconnect, onFrame)
    }

    /** Written once per frame; main-thread readers never see fields from mixed frames. */
    @Volatile public var geometry: PaneGeometry = PaneGeometry(); private set

    /** Pane thread only. */
    private var applied: GridSize? = null

    /** Row-text scratch frame, never the renderer's. Pane thread only. */
    private var scratch: FrameBuffer? = null

    /** Only the visual attachment sets this; bytes are always parsed at once and in order. */
    @Volatile public var priority: PanePriority = PanePriority.Background
        private set(value) {
            val raised = value.ordinal < field.ordinal
            field = value
            if (raised) post { publishFrame() }
        }

    private val statusFlow = MutableStateFlow(PaneStatus())
    public val status: StateFlow<PaneStatus> get() = statusFlow

    // Stream starts ride in the same queue as the bytes, so a reconnect's begin lands between the two.
    private val incoming = Drain<Any>(
        schedule = { run -> tryPost(run) },
        handle = { item ->
            if (!closed) when (item) {
                is Begin -> begin(item.nonce)
                is ByteString -> feed(item)
            }
        },
        // At least 8 KiB a message, so at most 32 one-line messages (about 0.2 ms of parsing) a drain:
        // the frame due every 8 ms waits on the same lane, behind whatever drain is running.
        weigh = { (it as? ByteString)?.size?.coerceAtLeast(8 * 1024) ?: 0 },
    )

    private val stream: DataStream = streamFactory(object : DataSink {
        override fun onStreamStart(nonce: Long) = incoming.offer(Begin(nonce))
        override fun onBytes(bytes: ByteString) = incoming.offer(bytes)
        override fun onState(state: DataStream.State) {
            // Called on the network thread while the pane thread also updates status: update atomically.
            statusFlow.update { now ->
                val failures = when (state) {
                    DataStream.State.Reconnecting -> now.failures + 1
                    DataStream.State.Live -> 0
                    else -> now.failures
                }
                now.copy(stream = state, failures = failures)
            }
        }
    })

    public fun start() {
        post {
            term = GhosttyTerminal().also { t -> localColors?.let { applyColors(t, it) } }
            savedFrame()?.let { bytes ->
                frames.backFrame().load(bytes)
                frames.publish()
                if (!statusFlow.value.restored) statusFlow.update { it.copy(cached = true) }
                attachments.notifyFrame()
            }
        }
        stream.start()
    }

    public fun captureFrame(save: (ByteArray) -> Unit) {
        post {
            val t = term ?: return@post
            if (!statusFlow.value.restored) return@post
            val frame = FrameBuffer()
            t.snapshot(frame)
            if (frame.cols > 0) save(frame.toBytes())
        }
    }

    public fun retry(): Unit = stream.retryNow()

    internal fun pause() { stream.pause() }

    internal fun unpause() { stream.unpause() }

    override fun close() {
        if (closed) return
        closed = true
        incoming.clear()
        attachments.close()
        stream.close()
        lane.launch {
            term?.close()
            term = null
        }
    }

    public fun scroll(lines: Int): Unit = post { shift = 0f; term?.scroll(lines); requestFrame() }

    public fun scrollPixels(pixels: Float, lineHeight: Float): Unit = post {
        val t = term ?: return@post
        val wanted = shift + pixels
        val from = t.viewportTop()
        val to = t.scroll(-floor(wanted / lineHeight).toInt())
        val left = wanted - (from - to) * lineHeight
        // The viewport stops at a scrollback end; a leftover offset there draws nothing.
        shift = if (to == 0 || left < 0f || left >= lineHeight) 0f else left
        requestFrame()
    }

    public fun settleScroll(lineHeight: Float): Unit = post {
        if (shift == 0f) return@post
        if (shift >= lineHeight / 2) term?.scroll(-1)
        shift = 0f
        requestFrame()
    }

    @Volatile public var scrollShift: Float = 0f
        private set

    private val searchFlow = MutableStateFlow<SearchInfo?>(null)

    public val search: StateFlow<SearchInfo?> get() = searchFlow
    private var searching = false

    public fun find(needle: String?): Unit = post {
        val t = term ?: return@post
        searching = !needle.isNullOrEmpty()
        t.search(needle)
        if (!searching) searchFlow.value = null
        requestFrame()
    }

    public fun findNext(older: Boolean): Unit = post {
        val t = term ?: return@post
        if (searching && t.searchSelect(older)) { shift = 0f; requestFrame() }
    }
    public fun scrollToBottom(): Unit = post { shift = 0f; term?.scrollToBottom(); requestFrame() }

    /** Local colours only draw here; colour queries from programs are answered on the host. */
    public fun setLocalColors(colors: TerminalColors): Unit = post {
        localColors = colors
        term?.let { applyColors(it, colors); requestFrame() }
    }

    private fun applyColors(t: GhosttyTerminal, c: TerminalColors) =
        t.setColors(c.foreground, c.background, c.cursor, c.palette.flatMapIndexed { i, rgb -> listOf(i, rgb) }.toIntArray())

    public fun setCursorDefaults(shape: CursorShape, blink: Boolean): Unit = post {
        cursor = shape to blink
        term?.setCursorDefaults(shape, blink)
        requestFrame()
    }

    public fun selectionText(a: ScreenCell, b: ScreenCell, result: (String?) -> Unit): Unit = ask(result) { it.selectionText(a, b) }

    public fun linkAt(col: Int, row: Int, result: (String?) -> Unit): Unit = ask(result) { it.linkAt(col, row) }

    public fun selectAll(result: (Pair<ScreenCell, ScreenCell>?) -> Unit): Unit = ask(result) { it.selectAll() }

    public fun rowText(row: Int, result: (RowText?) -> Unit): Unit = ask(result) { t ->
        val frame = scratch ?: FrameBuffer().also { scratch = it }
        t.snapshot(frame)
        if (row in 0 until frame.rows) readRow(frame, row) else null
    }

    /** Answers null once the pane is closed, so the caller never waits forever. */
    private fun <T> ask(result: (T?) -> Unit, work: (GhosttyTerminal) -> T?) {
        if (!tryPost { result(term?.let(work)) }) result(null)
    }

    // Pane thread from here on.

    private fun post(action: () -> Unit) { tryPost(action) }

    /** Queues [action] on the pane thread; false once the pane is closed, when nothing runs. */
    private fun tryPost(action: () -> Unit): Boolean {
        if (closed) return false
        lane.launch { action() }
        return true
    }

    private fun readRow(frame: FrameBuffer, row: Int): RowText {
        val text = StringBuilder()
        val columns = ArrayList<Int>(frame.cols)
        for (col in 0 until frame.cols) {
            val i = row * frame.cols + col
            if (frame.flags(i) and FrameBuffer.SPACER != 0) continue
            val before = text.length
            if (frame.codepoint(i) == 0) text.append(' ') else frame.appendText(i, text)
            repeat(text.length - before) { columns += col }
        }
        return RowText(text.toString(), columns.toIntArray(), frame.scrollOffset + row)
    }

    private fun begin(newNonce: Long) {
        nonce = newNonce
        snapshot = ByteBuffer.allocateDirect(256 * 1024)
        snapshotLength = 0
        statusFlow.update { it.copy(restored = false, exited = false) }
    }

    private fun feed(bytes: ByteString) {
        val t = term ?: return
        val pending = snapshot
        if (pending != null) return collectSnapshot(t, pending, bytes)
        // Data messages hold at most 64 KiB, so one bulk copy fits the reused buffer.
        if (bytes.size > input.capacity()) input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        input.clear()
        input.put(bytes.asByteBuffer())
        input.flip()
        // Trace sections (Hatch.*) are what scripts/trace.sh reads; they cost nothing while no trace runs.
        Trace.beginSection("Hatch.parse")
        try { t.write(input, ::onRexMessage) } finally { Trace.endSection() }
        requestFrame()
    }

    /** The stream opens with a Ghostty snapshot, collected whole before parsing. */
    private fun collectSnapshot(t: GhosttyTerminal, buffer: ByteBuffer, bytes: ByteString) {
        val grown = if (snapshotLength + bytes.size > buffer.capacity()) {
            ByteBuffer.allocateDirect(maxOf(buffer.capacity() * 2, snapshotLength + bytes.size)).also { bigger ->
                buffer.position(0).limit(snapshotLength)
                bigger.put(buffer)
            }
        } else buffer
        grown.position(snapshotLength)
        grown.put(bytes.asByteBuffer())
        snapshotLength += bytes.size
        snapshot = grown

        if (SnapshotScanner.notSnapshot(grown, snapshotLength)) return finishSnapshot(t, grown, 0)
        val end = SnapshotScanner.end(grown, snapshotLength)
        if (end < 0) return
        t.restore(grown, 0, end)
        t.setCursorDefaults(cursor.first, cursor.second)
        finishSnapshot(t, grown, end)
    }

    private fun finishSnapshot(t: GhosttyTerminal, buffer: ByteBuffer, from: Int) {
        snapshot = null
        buffer.position(from).limit(snapshotLength)
        t.write(buffer, ::onRexMessage)
        statusFlow.update { it.copy(restored = true, cached = false) }
        requestFrame()
    }

    /** A message with another nonce was printed by a program and decodes to null. */
    private fun onRexMessage(raw: String) {
        val t = term ?: return
        when (val msg = decodeInBand(raw, nonce) ?: return) {
            is InBandMessage.Resized -> {
                t.resize(msg.cols, msg.rows, msg.cellWidthPx, msg.cellHeightPx)
                applied = GridSize(msg.cols, msg.rows, msg.cellWidthPx, msg.cellHeightPx)
                statusFlow.update { it.copy(cols = msg.cols, rows = msg.rows) }
            }
            // This device draws in its own theme, whoever owns the pane.
            is InBandMessage.Themed -> localColors?.let { applyColors(t, it) }
                ?: t.setColors(msg.foreground, msg.background, msg.cursor, msg.palette.flatMap { listOf(it.first, it.second) }.toIntArray())
            is InBandMessage.Exited -> statusFlow.update { s -> s.copy(exited = true, exitCode = msg.exitCode, exitError = msg.error) }
        }
    }

    private fun requestFrame() {
        if (frameScheduled) return
        if (priority == PanePriority.Background && !searching) return
        frameScheduled = true
        // Paced from when the last frame was due, not when it ran: pacing from the run time added each
        // frame's lateness to the next, 9.3 ms apart (107 a second) on the Fold where 8.3 was meant.
        val now = System.nanoTime()
        frameDue = maxOf(now, frameDue + (if (priority == PanePriority.Focused) FRAME_NANOS else UNFOCUSED_FRAME_NANOS))
        val wait = frameDue - now
        lane.launch { delay(wait.nanoseconds); publishFrame() }
    }

    private fun publishFrame() {
        frameScheduled = false
        val t = term ?: return
        if (snapshot != null) return
        // Keep the saved frame until the live snapshot restores; the empty terminal would blank it.
        statusFlow.value.let { if (it.cached && !it.restored) return }
        val frame = frames.backFrame()
        Trace.beginSection("Hatch.snapshot")
        try { t.snapshot(frame, rowAbove = shift > 0f) } finally { Trace.endSection() }
        if (frame.rowsAbove == 0) shift = 0f
        // A program that never ends its hold sends no more frames: wake once past the limit.
        if (frame.held && !holdWake) { holdWake = true; lane.launch { delay(HOLD_LIMIT_MILLIS); holdWake = false; requestFrame() } }
        frame.shift = shift.toInt()
        scrollShift = shift
        geometry = PaneGeometry(
            applied = applied,
            displayed = GridSize(frame.cols, frame.rows, applied?.cellWidth ?: 0, applied?.cellHeight ?: 0),
            cursor = Cell(frame.cursorX, frame.cursorY).takeIf { frame.cursorY in 0 until frame.rows },
            scroll = Scroll(frame.scrollOffset, frame.scrollTotal, frame.rows),
            modes = Modes(frame.alternateScreen, frame.mouseTracking, frame.alternateScroll),
        )
        frames.publish()
        if (searching) {
            val info = t.searchInfo()
            searchFlow.value = info
            if (!info.complete) requestFrame()
        }
        attachments.notifyFrame()
    }

    private companion object {
        /** 120 Hz. */
        const val FRAME_NANOS = 8_333_333L
        /** 30 Hz: a quarter of the frames for panes nobody is typing in. */
        const val UNFOCUSED_FRAME_NANOS = 33_333_333L
        /** Just past the one second a program may hold the screen (FrameBuffer.held). */
        const val HOLD_LIMIT_MILLIS = 1_050L

    }
}
