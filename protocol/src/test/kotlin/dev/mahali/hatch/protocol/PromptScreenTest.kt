package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptScreenTest {
    private fun exercise(reply: (Envelope.Request) -> JsonObject, body: suspend (RexApi) -> Unit) = runBlocking {
        val server = MockWebServer()
        var peer: WebSocket? = null
        val closed = CompletableDeferred<Unit>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = decodeEnvelope(text)) {
                    is Envelope.Hello -> webSocket.send(Envelope.Welcome(Control.SUBPROTOCOL, Limits(1_048_576)).encode())
                    is Envelope.Request -> webSocket.send(Envelope.Response(message.id, payload = reply(message)).encode())
                    else -> Unit
                }
            }
        }).build())
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient()
        val control = RexControl(http, server.url("/").toString(), { ClientInfo(version = "test", label = "prompt-test") }, scope)
        try {
            control.start()
            withTimeout(5_000) { control.status.first { it is ControlStatus.Open } }
            body(RexApi(control))
        } finally {
            control.stop(); scope.cancel()
            peer?.close(1000, null)
            withTimeout(5_000) { closed.await() }
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.close()
        }
    }

    private fun metadata(request: Envelope.Request, pid: Long = 1): JsonObject? = when (request.method.substringAfterLast('.')) {
        "size" -> buildJsonObject { put("columns", 80); put("rows", 24) }
        "process" -> buildJsonObject { putJsonObject("foreground") { put("pid", pid); put("name", "codex"); put("path", "/bin/codex") } }
        "modes" -> buildJsonObject { put("screen", "primary") }
        else -> null
    }

    @Test fun `foreground preflight preserves executable identity without requesting terminal contents`() {
        val requests = AtomicInteger()
        exercise({ request ->
            requests.incrementAndGet()
            assertTrue(request.method.endsWith(".process"))
            buildJsonObject { putJsonObject("foreground") {
                put("pid", 42); put("name", "2.1.322"); put("path", "/opt/claude/versions/2.1.322")
                put("invoked_path", "/opt/bin/claude"); put("argv0", "claude")
            } }
        }) { api ->
            val process = api.foregroundProcess(SessionId("s"), BlockId("b"))
            assertEquals(42L, process.pid)
            assertEquals("/opt/claude/versions/2.1.322", process.path)
            assertEquals("/opt/bin/claude", process.invokedPath)
            assertEquals(1, requests.get())
        }
    }

    @Test fun `blob offsets use UTF8 bytes and entire export is verified`() {
        val offset = AtomicInteger(-1)
        exercise({ request ->
            metadata(request) ?: when (request.method.substringAfterLast('.')) {
                "format" -> buildJsonObject { put("content", "α"); put("id", 7); put("total", 4) }
                "blob_read" -> {
                    offset.set(request.payload.obj("args")!!.int("offset")!!)
                    buildJsonObject { put("data", "β"); put("eof", true) }
                }
                else -> error("Unexpected method")
            }
        }) { api ->
            val result = api.promptScreen(SessionId("session"), BlockId("block"))
            assertEquals("αβ", result.vt)
            assertEquals(2, offset.get())
            assertEquals(1L, result.identity.foreground.pid)
        }
    }

    @Test fun `oversized export stops before any blob download`() {
        val blobs = AtomicInteger()
        exercise({ request -> metadata(request) ?: if (request.method.endsWith("format")) {
            buildJsonObject { put("content", "head"); put("id", 7); put("total", 262_145) }
        } else { blobs.incrementAndGet(); JsonObject(emptyMap()) } }) { api ->
            val result = runCatching { api.promptScreen(SessionId("s"), BlockId("b")) }
            assertEquals("too_large", (result.exceptionOrNull() as PromptScreenUnavailable).reason)
            assertEquals(0, blobs.get())
        }
    }

    @Test fun `premature eof cannot turn a partial export into a screen`() {
        exercise({ request -> metadata(request) ?: if (request.method.endsWith("format")) {
            buildJsonObject { put("content", "a"); put("id", 7); put("total", 100) }
        } else buildJsonObject { put("data", "b"); put("eof", true) } }) { api ->
            val result = runCatching { api.promptScreen(SessionId("s"), BlockId("b")) }
            assertEquals("incomplete", (result.exceptionOrNull() as PromptScreenUnavailable).reason)
        }
    }

    @Test fun `process replacement during export invalidates its contents`() {
        val processes = AtomicInteger()
        exercise({ request ->
            val pid = if (request.method.endsWith("process")) processes.incrementAndGet().toLong() else 1L
            metadata(request, pid) ?: buildJsonObject { put("content", "screen"); put("total", 6) }
        }) { api ->
            val result = runCatching { api.promptScreen(SessionId("s"), BlockId("b")) }
            assertEquals("changed", (result.exceptionOrNull() as PromptScreenUnavailable).reason)
            assertTrue(processes.get() >= 2)
        }
    }
}
