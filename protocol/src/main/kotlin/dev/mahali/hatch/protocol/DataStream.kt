package dev.mahali.hatch.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.coroutineContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives one block's stream, in order, on the network thread. Implementations hand bytes to their own thread. */
public interface DataSink {
    /** A new stream begins: drop old content; the bytes that follow start with a Ghostty snapshot. */
    public fun onStreamStart(nonce: Long)
    public fun onBytes(bytes: ByteString)
    public fun onState(state: DataStream.State)
}

/**
 * One data connection (`/api/data`) carrying a block's stream. Closing with status 1000 ends the stream on the server.
 *
 * One generation owns the socket. Replacing it bumps the generation under the byte-count lock, so a late message cannot move the resume offset.
 */
public class DataStream(
    private val http: OkHttpClient,
    baseUrl: String,
    private val subprotocol: String,
    private val ticket: suspend () -> Ticket,
    private val sink: DataSink,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val diagnostics: ConnectionDiagnostics? = null,
    private val streamId: String = "stream",
) {
    public enum class State { Opening, Live, Reconnecting, BlockClosed, Ended }

    private val url = baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/data"

    private val gate = Any()
    private var epoch = 0L
    private var socket: WebSocket? = null
    private var token: ByteArray? = null
    private var delivered = 0L
    private var welcomed = false
    private var ended = false
    private var paused = false
    private var attempts = 0
    private var retry: Job? = null
    private var connecting: Job? = null

    public fun start() {
        diagnostics?.streamWaiting(streamId)
        sink.onState(State.Opening)
        connecting = scope.launch { fresh() }
    }

    /** Any close but 1000 keeps the stream alive on the server for 60 s; 1000 would end it and force a new snapshot. */
    public fun pause() {
        diagnostics?.streamStopped(streamId)
        val old = synchronized(gate) {
            if (ended || paused) return
            paused = true
            retry?.cancel()
            connecting?.cancel()
            invalidate()
        }
        old?.close(1001, "paused")
    }

    public fun unpause() {
        val resume = synchronized(gate) {
            if (ended || !paused) return
            paused = false
            attempts = 0
            token != null
        }
        connecting = scope.launch { if (resume) openResume() else fresh() }
    }

    public fun retryNow() {
        val old = synchronized(gate) {
            if (ended) return
            retry?.cancel()
            connecting?.cancel()
            invalidate()
        }
        old?.cancel()
        connecting = scope.launch {
            val resume = synchronized(gate) { token != null }
            if (resume) openResume() else fresh()
        }
    }

    public fun close() {
        diagnostics?.streamStopped(streamId)
        val old = synchronized(gate) {
            ended = true
            retry?.cancel()
            connecting?.cancel()
            invalidate()
        }
        old?.close(1000, null)
    }

    private suspend fun fresh() {
        if (synchronized(gate) { ended || paused }) return
        try {
            val t = ticket()
            if (!coroutineContext.isActive) return
            openFresh(byteArrayOf(HELLO) + t.bytes)
        } catch (e: RexError) {
            if (e.code == "not_found" || e.code == "session_not_found") return blockClosed()
            diagnostics?.failure("terminal stream $streamId ticket failed: ${e.message ?: e.code}")
            retryLater()
        }
    }

    private class Open(val mine: Long, val previous: WebSocket?, val handshake: ByteArray)

    private fun openResume() {
        val open = synchronized(gate) {
            if (ended || paused) return
            val current = token?.copyOf() ?: return@synchronized null
            // Offset and generation change under the byte-count lock: in-flight bytes are counted first.
            val retired = invalidate()
            Open(epoch, retired, resumeMessage(current, delivered))
        } ?: run {
            connecting = scope.launch { fresh() }
            return
        }
        open.previous?.cancel()
        connect(open.mine, open.handshake, resuming = true)
    }

    private fun openFresh(hello: ByteArray) {
        val open = synchronized(gate) {
            if (ended || paused) return
            token = null
            delivered = 0
            val retired = invalidate()
            Open(epoch, retired, hello)
        }
        open.previous?.cancel()
        connect(open.mine, open.handshake, resuming = false)
    }

    private fun connect(mine: Long, handshake: ByteArray, resuming: Boolean) {
        diagnostics?.streamWaiting(streamId)
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", subprotocol).build()
        // onOpen can run before newWebSocket returns, so the listener keys on generation, not the socket field.
        val created = http.newWebSocket(request, listener(mine, handshake, resuming))
        val stale = synchronized(gate) {
            if (epoch != mine || ended) true else { socket = created; false }
        }
        if (stale) created.cancel()
    }

    private fun listener(mine: Long, handshake: ByteArray, resuming: Boolean) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val send = synchronized(gate) { epoch == mine && !ended }
            if (send) webSocket.send(handshake.toByteString()) else webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val reject = synchronized(gate) {
                if (epoch != mine || ended) return
                if (!welcomed) {
                    val welcome = parseWelcome(bytes)
                    if (welcome == null) {
                        token = null
                        true
                    } else {
                        token = welcome.token
                        welcomed = true
                        attempts = 0
                        if (!resuming) sink.onStreamStart(welcome.nonce)
                        diagnostics?.streamReady(streamId, resuming)
                        sink.onState(State.Live)
                        false
                    }
                } else {
                    takeBytes(mine, bytes.size) { sink.onBytes(bytes) }
                    false
                }
            }
            if (reject) webSocket.cancel()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val seen = synchronized(gate) { delivered }
            log("data closed $code $reason after $seen bytes")
            dropped(mine, code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val current = synchronized(gate) { epoch == mine && !ended && !paused }
            if (!current) return
            diagnostics?.failure("terminal stream $streamId failed: ${t.message ?: t.javaClass.simpleName}")
            val seen = synchronized(gate) { delivered }
            log("data failed ${response?.code ?: ""} ${t.javaClass.simpleName}: ${t.message} after $seen bytes")
            dropped(mine, -1)
        }
    }

    private fun dropped(mine: Long, code: Int) {
        val next = synchronized(gate) {
            if (epoch != mine || ended) return
            invalidate()
            when (code) {
                1000 -> { ended = true; Drop.Ended }
                4000 -> { ended = true; Drop.BlockClosed }
                1013 -> { token = null; Drop.Retry }
                else -> Drop.Retry
            }
        }
        when (next) {
            Drop.Ended -> { diagnostics?.streamStopped(streamId); sink.onState(State.Ended) }
            Drop.BlockClosed -> { diagnostics?.streamStopped(streamId); sink.onState(State.BlockClosed) }
            Drop.Retry -> retryLater()
        }
    }

    private fun blockClosed() {
        synchronized(gate) {
            ended = true
            invalidate()
        }
        diagnostics?.streamStopped(streamId)
        sink.onState(State.BlockClosed)
    }

    private fun retryLater() {
        val wait = synchronized(gate) {
            if (ended || paused) return
            diagnostics?.streamWaiting(streamId)
            val delay = (500L shl attempts.coerceAtMost(4)).coerceAtMost(5_000)
            attempts++
            delay
        }
        sink.onState(State.Reconnecting)
        retry = scope.launch {
            delay(wait)
            val step = synchronized(gate) {
                if (ended || paused || socket != null) return@launch
                if (token != null) 1 else 0
            }
            if (step == 1) openResume() else fresh()
        }
    }

    /** Bumps the generation and detaches the socket. Caller cancels it outside the lock. */
    private fun invalidate(): WebSocket? {
        epoch += 1
        welcomed = false
        val old = socket
        socket = null
        return old
    }

    /** [deliver] runs under the generation lock: a replacement cannot begin until it returns. */
    internal fun takeBytes(mine: Long, size: Int, deliver: () -> Unit): Boolean = synchronized(gate) {
        if (epoch != mine || !welcomed || ended) return false
        delivered += size.toLong()
        deliver()
        true
    }

    internal fun retire(): Long = synchronized(gate) {
        invalidate()
        epoch
    }

    internal fun noteWelcome(mine: Long): Boolean = synchronized(gate) {
        if (epoch != mine || ended) return false
        welcomed = true
        true
    }

    internal fun deliveredBytes(): Long = synchronized(gate) { delivered }

    private enum class Drop { Ended, BlockClosed, Retry }

    public companion object {
        public const val TERMINAL: String = "rex.data.terminal.v1"
        public const val BYTES: String = "rex.data.bytes.v1"
        internal const val HELLO: Byte = 0x01
        internal const val RESUME: Byte = 0x02

        internal fun resumeMessage(token: ByteArray, delivered: Long): ByteArray =
            ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).put(RESUME).put(token).putLong(delivered).array()
    }
}

/** A WELCOME: `0x81`, a 16-byte reconnect token, 3 zero bytes, keep-alive ms (u32), channel nonce (u64). */
internal class Welcome(val token: ByteArray, val keepMillis: Long, val nonce: Long)

internal fun parseWelcome(bytes: ByteString): Welcome? {
    if (bytes.size != 32 || bytes[0] != 0x81.toByte()) return null
    val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
    return Welcome(bytes.substring(1, 17).toByteArray(), b.getInt(20).toLong() and 0xFFFF_FFFFL, b.getLong(24))
}
