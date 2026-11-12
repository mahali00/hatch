package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.SessionId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteFileTest {
    private class Server : AutoCloseable {
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val controlServer = MockWebServer()
        private val dataServer = MockWebServer()
        val blocks = ConcurrentHashMap<String, String>()
        val closed = CopyOnWriteArrayList<String>()
        val created = AtomicInteger()
        val ranges = Channel<Envelope.Request>(Channel.UNLIMITED)
        private val sockets = ConcurrentHashMap<String, WebSocket>()
        @Volatile var holdRanges = false
        val control: RexControl
        val api: RexApi

        init {
            controlServer.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val message = decodeEnvelope(text)) {
                        is Envelope.Hello -> webSocket.send(Envelope.Welcome(Control.SUBPROTOCOL, Limits(1_048_576)).encode())
                        is Envelope.Request -> {
                            val payload = handle(message)
                            webSocket.send(Envelope.Response(message.id, payload = payload).encode())
                        }
                        else -> Unit
                    }
                }
            }).build())
            controlServer.start()
            dataServer.start()
            control = RexControl(http, controlServer.url("/").toString(), { ClientInfo(version = "test", label = "same-phone-model") }, scope)
            api = RexApi(control)
        }

        suspend fun open(owner: UUID): RemoteFile {
            control.start()
            withTimeout(5_000) { control.status.first { it is ControlStatus.Open } }
            return RemoteFile.open(api, http, dataServer.url("/").toString(), SessionId("session"), "/preview.txt", scope, owner)
        }

        private fun handle(request: Envelope.Request): JsonObject = buildJsonObject {
            when (request.method) {
                "server.version" -> put("platform", "linux")
                "session.list_blocks" -> put("blocks", buildJsonArray {
                    blocks.forEach { (id, label) -> add(buildJsonObject {
                        put("block_id", id); put("label", label); put("placement", "detached")
                    }) }
                })
                "session.new_block" -> {
                    val id = "helper-${created.incrementAndGet()}"
                    blocks[id] = request.payload.getValue("label").jsonPrimitive.content
                    dataServer.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            sockets[id] = webSocket
                            webSocket.send(ByteArray(32).apply { this[0] = 0x81.toByte() }.toByteString())
                        }
                    }).build())
                    put("block_id", id)
                }
                "data.connect" -> put("ticket", Base64.getUrlEncoder().encodeToString(ByteArray(16)))
                "com.superlogical.terminal.write" -> {
                    val id = request.payload.getValue("block_id").jsonPrimitive.content
                    val data = request.payload.getValue("args").jsonObject.getValue("data").jsonPrimitive.content
                    if (data == "go\n") sockets.getValue(id).send("HATCH-META 8\r\n".encodeToByteArray().toByteString())
                    else {
                        ranges.trySend(request)
                        if (!holdRanges) finishRange(request)
                    }
                }
                "block.close" -> {
                    val id = request.payload.getValue("block_id").jsonPrimitive.content
                    closed += id
                    blocks.remove(id)
                }
                else -> error("Unexpected RPC: ${request.method}")
            }
        }

        fun finishRange(request: Envelope.Request) {
            val id = request.payload.getValue("block_id").jsonPrimitive.content
            val data = request.payload.getValue("args").jsonObject.getValue("data").jsonPrimitive.content.trim().split(' ')
            val offset = data[0].toInt()
            val length = data[1].toInt()
            val encoded = Base64.getEncoder().encodeToString("abcdefgh".encodeToByteArray().copyOfRange(offset, offset + length))
            sockets.getValue(id).send("HATCH-BEGIN $offset $length\r\n$encoded\r\nHATCH-END $offset\r\n".encodeToByteArray().toByteString())
        }

        override fun close() {
            control.stop()
            scope.cancel()
            controlServer.close()
            dataServer.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun orphanSweepOnlyClosesTheMatchingInstallationNotAnotherSameModelPhone() = runBlocking {
        Server().use { server ->
            val owner = UUID.randomUUID()
            val other = UUID.randomUUID()
            server.blocks["ours"] = "hatch-preview $owner"
            server.blocks["other-phone"] = "hatch-preview $other"
            val file = withTimeout(5_000) { server.open(owner) }
            try {
                assertEquals(listOf("ours"), server.closed.toList())
                assertEquals("hatch-preview $other", server.blocks["other-phone"])
                assertContentEquals("abcdefgh".encodeToByteArray(), file.read(0, 8))
            } finally {
                file.dispose()
            }
        }
    }

    @Test fun aReadQueuedAfterDisposeCannotReviveTheHelper() = runBlocking {
        Server().use { server ->
            val file = withTimeout(5_000) { server.open(UUID.randomUUID()) }
            try {
                server.holdRanges = true
                withTimeout(5_000) {
                    val first = async { file.read(0, 4) }
                    val request = server.ranges.receive()
                    val close = async(start = CoroutineStart.UNDISPATCHED) { file.dispose() }
                    val late = async(start = CoroutineStart.UNDISPATCHED) {
                        assertFailsWith<RexError> { file.read(4, 4) }
                    }
                    server.finishRange(request)
                    assertContentEquals("abcd".encodeToByteArray(), first.await())
                    close.await()
                    assertEquals("closed", late.await().code)
                }
                assertEquals(1, server.created.get())
                assertEquals(listOf("helper-1"), server.closed.toList())
            } finally {
                file.dispose()
            }
        }
    }
}
