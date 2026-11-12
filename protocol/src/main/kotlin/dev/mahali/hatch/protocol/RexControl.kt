package dev.mahali.hatch.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import dev.mahali.hatch.core.normalizeAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** [code] is the server's error code, or `disconnected` when the socket dropped. */
public class RexError(public val code: String, message: String) : Exception(message)

/** [seq] is the last event reflected; [generation] is the connection the call was sent on. */
public data class Reply(val payload: JsonObject, val seq: Long, val generation: Long)

/** Read by one consumer ([HostSync]), in order. */
public sealed interface ControlSignal {
    public data class Opened(val welcome: Envelope.Welcome, val resumed: Boolean) : ControlSignal
    public data class Event(val seq: Long, val event: JsonObject) : ControlSignal
    public data class Dropped(val reason: String) : ControlSignal
}

public sealed interface ControlStatus {
    public data object Stopped : ControlStatus
    public data class Connecting(val attempt: Int, val retryAtMillis: Long? = null, val lastError: String? = null) : ControlStatus
    public data class Open(val clientId: String?) : ControlStatus
}

/**
 * Control connection to one Rex server (`/api/control`, subprotocol `rex.control.v1`). The server keeps a client
 * for 60 s after a drop when the reconnect token is sent. [signals] has one consumer: [HostSync].
 */
public class RexControl(
    http: OkHttpClient,
    baseUrl: String,
    private val client: () -> ClientInfo,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Never receives the reconnect token. */
    private val log: (String) -> Unit = {},
) {
    public val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(clock)
    private val resolver = http.dns
    private val http = http.newBuilder().dns { hostname ->
        val started = System.nanoTime()
        resolver.lookup(hostname).also { addresses ->
            diagnostics.resolved((System.nanoTime() - started) / 1_000_000, addresses.joinToString { it.hostAddress.orEmpty() })
        }
    }.eventListenerFactory {
        object : EventListener() {
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
                diagnostics.endpoint("${inetSocketAddress.address?.hostAddress ?: inetSocketAddress.hostString}:${inetSocketAddress.port} · ${if (url?.startsWith("wss:") == true) "TLS" else "WebSocket"}")
            }
        }
    }.build()
    private val address = normalizeAddress(baseUrl)
    private val url = address.getOrNull()?.trimEnd('/')?.replaceFirst("https://", "wss://")?.replaceFirst("http://", "ws://")?.plus("/api/control")
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Envelope.Response>>()
    private val signalChannel = Channel<ControlSignal>(Channel.UNLIMITED)
    private val statusFlow = MutableStateFlow<ControlStatus>(ControlStatus.Stopped)

    private val gate = Any()
    private var epoch = 0L
    /** Increments on each welcome, before calls on that connection are accepted. */
    private var generation = 0L
    private var onWelcome: ((Long) -> Unit)? = null
    private var socket: WebSocket? = null
    private var welcomed = false
    private var reconnectToken: String? = null
    private var running = false
    private var attempt = 0
    private var retry: Job? = null

    /** Runs on the socket thread as a connection is welcomed, before any call on it can start. */
    internal fun bindWelcome(listener: (Long) -> Unit) {
        onWelcome = listener
    }

    public val signals: ReceiveChannel<ControlSignal> get() = signalChannel
    public val status: StateFlow<ControlStatus> get() = statusFlow

    public fun start() {
        val go = synchronized(gate) {
            if (running) false else { running = true; attempt = 0; true }
        }
        if (go) connect()
    }

    /** The close frame tells the server not to keep the client. */
    public fun stop() {
        val old = synchronized(gate) {
            running = false
            retry?.cancel()
            retire()
        }
        old?.close(1000, null)
        failPending("stopped")
        statusFlow.value = ControlStatus.Stopped
    }

    public fun reconnectNow() {
        val go = synchronized(gate) {
            if (!running) false else { retry?.cancel(); attempt = 0; true }
        }
        if (!go) return start()
        connect()
    }

    public suspend fun call(method: String, payload: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 30_000): Reply {
        val id = ids.incrementAndGet().toString()
        val answer = CompletableDeferred<Envelope.Response>()
        val ws: WebSocket
        val generation: Long
        synchronized(gate) {
            ws = socket?.takeIf { welcomed } ?: throw RexError("disconnected", "Not connected")
            generation = this.generation
            pending[id] = answer
        }
        diagnostics.requestStarted()
        try {
            if (!ws.send(Envelope.Request(id, method, payload).encode())) throw RexError("disconnected", "Send failed")
            val response = withTimeoutOrNull(timeoutMillis) { answer.await() }
                ?: throw RexError("timeout", "$method timed out")
            response.error?.let { throw RexError(it.code, it.message) }
            return Reply(response.payload ?: JsonObject(emptyMap()), response.seq, generation)
        } finally {
            pending.remove(id)
            diagnostics.requestFinished()
        }
    }

    /** RTT and TCP checks, requested only while diagnostics is visible. */
    public suspend fun probe(): Unit = kotlinx.coroutines.coroutineScope {
        if (welcomed) launch {
            val started = System.nanoTime()
            try {
                call("server.version", timeoutMillis = 2_500)
                diagnostics.probe((System.nanoTime() - started) / 1_000_000)
            } catch (e: RexError) {
                diagnostics.probeFailed(e.message ?: e.code)
            }
        }
        address.getOrNull()?.let { endpoint -> launch {
            val target = java.net.URI(endpoint)
            val port = if (target.port >= 0) target.port else if (target.scheme == "https") 443 else 80
            diagnostics.reachability(probePort(target.host, port, resolver))
        } }
    }

    private fun connect() {
        val mine: Long
        val previous: WebSocket?
        val attemptNow: Int
        val abandoned = synchronized(gate) {
            // Bump before the new socket exists: the old socket's callbacks, even an early welcome, then see a stale generation.
            epoch += 1
            mine = epoch
            previous = socket
            socket = null
            welcomed = false
            attemptNow = attempt
            val waiting = pending.values.toList()
            pending.clear()
            waiting
        }
        previous?.cancel()
        abandoned.forEach { it.completeExceptionally(RexError("disconnected", "reconnecting")) }
        diagnostics.connecting(attemptNow)
        // A retry keeps the last failure until a connection succeeds, so an unreachable host doesn't flash back to "Connecting".
        statusFlow.value = ControlStatus.Connecting(attemptNow, lastError = (statusFlow.value as? ControlStatus.Connecting)?.lastError)
        log("control connecting (attempt $attemptNow)")
        val endpoint = url
        if (endpoint == null) {
            val message = address.exceptionOrNull()?.message ?: "Invalid server address"
            diagnostics.dropped(message)
            statusFlow.value = ControlStatus.Connecting(attemptNow, lastError = message)
            return
        }
        val request = Request.Builder().url(endpoint).header("Sec-WebSocket-Protocol", Control.SUBPROTOCOL).build()
        val created = http.newWebSocket(request, Listener(mine))
        val stale = synchronized(gate) {
            if (epoch != mine || !running) true else { if (socket == null) socket = created; false }
        }
        if (stale) created.cancel()
    }

    private inner class Listener(private val mine: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = synchronized(gate) {
                if (epoch != mine) null
                else {
                    socket = webSocket
                    val info = client().let { it.copy(label = it.label.take(40)) }
                    Envelope.Hello(client = info, reconnectToken = reconnectToken).encode()
                }
            } ?: return webSocket.cancel()
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { decodeEnvelope(text) }.getOrNull()
            when (message) {
                is Envelope.Welcome -> {
                    if (message.protocol != Control.SUBPROTOCOL) { webSocket.close(1002, "unexpected protocol"); return }
                    val accepted = synchronized(gate) {
                        if (epoch != mine) return
                        generation += 1
                        // Published before welcomed, so a call cannot start on this connection under the old generation.
                        onWelcome?.invoke(generation)
                        reconnectToken = message.client?.reconnectToken
                        welcomed = true
                        socket = webSocket
                        attempt = 0
                        true
                    }
                    if (!accepted) return
                    diagnostics.connected(message.client?.resumed == true)
                    log("control welcomed, api revision ${message.apiRevision}, resumed ${message.client?.resumed == true}")
                    statusFlow.value = ControlStatus.Open(message.client?.id)
                    signalChannel.trySend(ControlSignal.Opened(message, message.client?.resumed == true))
                }
                is Envelope.Response -> {
                    val waiting = synchronized(gate) { if (epoch != mine) null else pending.remove(message.id) }
                    waiting?.complete(message)
                }
                is Envelope.Event -> synchronized(gate) {
                    if (epoch == mine) signalChannel.trySend(ControlSignal.Event(message.seq, message.payload))
                }
                else -> Unit // a type this client does not know: skip it, as the protocol asks
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            dropped(mine, if (reason.isNotEmpty()) "$reason ($code)" else "closed ($code)")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            dropped(mine, response?.let { "HTTP ${it.code}" } ?: failureReason(t, webSocket.request().url.host))
    }

    private fun dropped(mine: Long, reason: String) {
        val wait: Long
        val next: Int
        val stillRunning: Boolean
        synchronized(gate) {
            if (epoch != mine) return
            epoch += 1
            socket = null
            welcomed = false
            stillRunning = running
            // Rex.nudge (network change, foreground) skips this wait.
            wait = (1000L shl attempt.coerceAtMost(3)).coerceAtMost(5_000)
            attempt++
            next = attempt
        }
        diagnostics.dropped(reason)
        log("control dropped: $reason")
        failPending(reason)
        signalChannel.trySend(ControlSignal.Dropped(reason))
        if (!stillRunning) return
        statusFlow.value = ControlStatus.Connecting(next, clock() + wait, reason)
        retry = scope.launch {
            delay(wait)
            val again = synchronized(gate) { running && socket == null }
            if (again) connect()
        }
    }

    // Lock must be held; the caller closes the returned socket outside it.
    private fun retire(): WebSocket? {
        epoch += 1
        val old = socket
        socket = null
        welcomed = false
        return old
    }

    private fun failPending(reason: String) {
        val waiting = synchronized(gate) {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        waiting.forEach { it.completeExceptionally(RexError("disconnected", reason)) }
    }
}

/** A tailnet name that doesn't resolve almost always means MagicDNS is off on this device. */
internal fun failureReason(t: Throwable, host: String): String =
    if (t is UnknownHostException && host.endsWith(".ts.net")) "Couldn't find $host. Turn on MagicDNS in Tailscale, or use the server's 100.x address."
    else t.message ?: t.javaClass.simpleName
