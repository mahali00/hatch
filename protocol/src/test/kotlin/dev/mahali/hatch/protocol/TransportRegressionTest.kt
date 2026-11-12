package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportRegressionTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private inner class Server(val handle: (WebSocket, Envelope.Request) -> Unit) : AutoCloseable {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient()
        val control: RexControl
        init {
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val message = decodeEnvelope(text)) {
                        is Envelope.Hello -> webSocket.send(Envelope.Welcome(Control.SUBPROTOCOL, Limits(1_048_576)).encode())
                        is Envelope.Request -> handle(webSocket, message)
                        else -> Unit
                    }
                }
            }).build())
            server.start()
            control = RexControl(http, server.url("/").toString(), { ClientInfo(version = "test", label = "test") }, scope)
        }
        override fun close() { control.stop(); scope.cancel(); server.close(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun `own request timeout is an error while parent cancellation remains cancellation`() = runBlocking {
        Server { _, _ -> }.use { s ->
            s.control.start()
            withTimeout(5000) { s.control.status.first { it is ControlStatus.Open } }
            val failure = assertFailsWith<RexError> { s.control.call("client.key", timeoutMillis = 25) }
            assertEquals("timeout", failure.code)
            assertEquals(0, s.control.diagnostics.state.value.pendingRequests)
            val caller = launch { s.control.call("client.key", timeoutMillis = 5000) }
            withTimeout(5000) { s.control.diagnostics.state.first { it.pendingRequests == 1 } }
            caller.cancelAndJoin()
            assertEquals(0, s.control.diagnostics.state.value.pendingRequests)
        }
    }

    @Test fun `invalid saved address becomes connection error without throwing`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient()
        val control = RexControl(http, "not a url", { ClientInfo(version = "test", label = "test") }, scope)
        try {
            control.start()
            assertNotNull((control.status.value as ControlStatus.Connecting).lastError)
        } finally { control.stop(); scope.cancel(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun `ready waits for the session attach result`() = runBlocking {
        val attach = CompletableDeferred<Pair<WebSocket, Envelope.Request>>()
        Server { ws, request ->
            when (request.method) {
                "session.bootstrap" -> ws.send(Envelope.Response(request.id, 1, json("""{"client_id":"client","attach_all":{"sessions":[{"session_id":"s","label":"Session"}]}}""")).encode())
                "session.attach" -> attach.complete(ws to request)
                else -> ws.send(Envelope.Response(request.id, 1, JsonObject(emptyMap())).encode())
            }
        }.use { s ->
            val sync = HostSync(Host(HostId("host"), "Host", s.server.url("/").toString()), s.control, s.scope)
            sync.start()
            val (ws, request) = withTimeout(5000) { attach.await() }
            assertNull(s.control.diagnostics.state.value.readyMillis)
            ws.send(Envelope.Response(request.id, 1, json("""{"view":{"session_id":"s","label":"Session","revision":1,"windows":[]}}""")).encode())
            val measured = withTimeout(5000) { s.control.diagnostics.state.first { it.readyMillis != null } }
            assertNotNull(measured.attachMillis)
            assertEquals(1, measured.attachedSessions)
        }
    }

    @Test fun `detail replies computed before live events cannot erase newer titles or status`() = runBlocking {
        val statusReplied = CompletableDeferred<Unit>()
        val view = json("""{"session_id":"s","label":"Session","revision":1,"active_window_id":"w","windows":[{"window_id":"w","label":"Window","focused_block_id":"b","layers":[{"kind":"tiled","layout":{"block_id":"b"},"blocks":[{"block_id":"b","label":"shell"}]}]}]}""")
        Server { ws, request ->
            fun reply(payload: JsonObject, seq: Long = 1) { ws.send(Envelope.Response(request.id, seq, payload).encode()) }
            fun event(seq: Long, name: String, payload: String) {
                ws.send(Envelope.Event(seq, json("""{"type":"session_event","payload":{"session_id":"s","event":{"type":"block_event","payload":{"block_id":"b","name":"$name","payload":$payload}}}}""")).encode())
            }
            when (request.method) {
                "session.bootstrap" -> reply(json("""{"client_id":"client","attach_all":{"sessions":[{"session_id":"s","label":"Session"}]}}"""))
                "session.attach" -> reply(kotlinx.serialization.json.buildJsonObject { put("view", view) })
                "com.superlogical.terminal.title" -> { event(2, "title_changed", """{"title":"new title"}"""); reply(json("""{"title":"old title"}""")) }
                "com.superlogical.terminal.program_status" -> {
                    event(3, "program_status_changed", """{"id":"job","record":{"state":"working"}}""")
                    reply(json("""{"records":[]}""")); statusReplied.complete(Unit)
                }
                else -> reply(JsonObject(emptyMap()))
            }
        }.use { s ->
            val sync = HostSync(Host(HostId("host"), "Host", s.server.url("/").toString()), s.control, s.scope)
            sync.start()
            withTimeout(5000) { statusReplied.await(); s.control.diagnostics.state.first { it.pendingRequests == 0 } }
            delay(100)
            val block = sync.state.value.blocksById[BlockId("b")]
            assertNotNull(block)
            assertEquals("new title", block.title)
            assertEquals(ProgramState.Working, block.status["job"]?.state)
            assertTrue(sync.state.value.sessions.isNotEmpty())
        }
    }

    @Test fun `an attach reply from the replaced connection does not overwrite the new session`() = runBlocking {
        val bootstraps = Channel<Pair<WebSocket, Envelope.Request>>(4)
        val attaches = Channel<Pair<WebSocket, Envelope.Request>>(4)
        val server = MockWebServer()
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listener = object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = decodeEnvelope(text)) {
                    is Envelope.Hello -> webSocket.send(Envelope.Welcome(Control.SUBPROTOCOL, Limits(1_048_576), WelcomeClient("c", "token")).encode())
                    is Envelope.Request -> when (message.method) {
                        "session.bootstrap" -> bootstraps.trySend(webSocket to message)
                        "session.attach", "session.view" -> attaches.trySend(webSocket to message)
                        else -> webSocket.send(Envelope.Response(message.id, 1, JsonObject(emptyMap())).encode())
                    }
                    else -> Unit
                }
            }
        }
        repeat(3) { server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build()) }
        server.start()
        val control = RexControl(http, server.url("/").toString(), { ClientInfo(version = "test", label = "test") }, scope)
        val sync = HostSync(Host(HostId("host"), "Host", server.url("/").toString()), control, scope)
        try {
            sync.start()
            val (first, bootstrap) = withTimeout(5_000) { bootstraps.receive() }
            first.send(Envelope.Response(bootstrap.id, 1, json("""{"client_id":"old","attach_all":{"sessions":[{"session_id":"s","label":"Fresh"}]}}""")).encode())
            withTimeout(5_000) { sync.state.first { it.clientId == "old" } }
            val refresh = launch { runCatching { sync.refresh(SessionId("s")) } }
            val (attachSocket, attach) = withTimeout(5_000) { attaches.receive() }
            control.reconnectNow()
            val (second, again) = withTimeout(5_000) { bootstraps.receive() }
            second.send(Envelope.Response(again.id, 1, json("""{"client_id":"new","attach_all":{"sessions":[{"session_id":"s","label":"Fresh"}]}}""")).encode())
            withTimeout(5_000) { sync.state.first { it.clientId == "new" } }
            runCatching {
                attachSocket.send(Envelope.Response(attach.id, 1, json(
                    """{"view":{"session_id":"s","label":"Stale","revision":9,"active_window_id":"w","windows":[{"window_id":"w","label":"Window","focused_block_id":"b","layers":[{"kind":"tiled","layout":{"block_id":"b"},"blocks":[{"block_id":"b","label":"shell"}]}]}]}}""",
                )).encode())
            }
            withTimeout(5_000) { refresh.join() }
            delay(200)
            assertEquals("new", sync.state.value.clientId)
            assertEquals("Fresh", sync.state.value.sessionsById[SessionId("s")]?.label)
            assertTrue((sync.state.value.sessionsById[SessionId("s")]?.revision ?: -1) < 9)
        } finally {
            control.stop()
            scope.cancel()
            server.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
