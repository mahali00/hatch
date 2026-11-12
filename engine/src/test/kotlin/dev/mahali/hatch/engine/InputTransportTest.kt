package dev.mahali.hatch.engine

import dev.mahali.hatch.protocol.ClientInfo
import dev.mahali.hatch.protocol.Control
import dev.mahali.hatch.protocol.ControlStatus
import dev.mahali.hatch.protocol.Envelope
import dev.mahali.hatch.protocol.Limits
import dev.mahali.hatch.protocol.RexControl
import dev.mahali.hatch.protocol.decodeEnvelope
import dev.mahali.hatch.protocol.encode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTransportTest {
    @Test fun `slow socket replies bound real requests and keep mixed input in wire order`() = runBlocking {
        val requests = LinkedBlockingQueue<Envelope.Request>()
        val failures = LinkedBlockingQueue<Exception>()
        var peer: WebSocket? = null
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = decodeEnvelope(text)) {
                    is Envelope.Hello -> webSocket.send(Envelope.Welcome(Control.SUBPROTOCOL, Limits(1_048_576)).encode())
                    is Envelope.Request -> requests.add(message)
                    else -> Unit
                }
            }
        }).build())
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient()
        val control = RexControl(http, server.url("/").toString(), { ClientInfo(version = "test", label = "test") }, scope)
        val input = OrderedInput(scope, { failures.add(it) }, maxInFlight = 4)
        fun next(): Envelope.Request = requests.poll(5, TimeUnit.SECONDS) ?: error("Request did not reach socket")
        fun reply(request: Envelope.Request) { check(peer!!.send(Envelope.Response(request.id, payload = JsonObject(emptyMap())).encode())) }
        try {
            control.start()
            withTimeout(5000) { control.status.first { it is ControlStatus.Open } }
            val methods = List(64) { if (it % 3 == 0) "block.event" else "client.key" }
            assertTrue(input.submit(1024, methods.mapIndexed { i, method -> suspend { control.call(method, buildJsonObject { put("order", i) }); Unit } }.asSequence()))
            val first = List(4) { next() }
            assertNull(requests.poll(150, TimeUnit.MILLISECONDS))
            assertEquals(4, control.diagnostics.state.value.pendingRequests)
            reply(first[2])
            val fifth = next()
            first.filterIndexed { i, _ -> i != 2 }.forEach(::reply)
            reply(fifth)
            val rest = List(59) { next().also(::reply) }
            val all = first + fifth + rest
            assertEquals(methods, all.map { it.method })
            assertEquals((0..63).map { it.toString() }, all.map { it.payload.getValue("order").jsonPrimitive.content })
            withTimeout(5000) { input.load.first { it.batches == 0 } }
            assertTrue(failures.isEmpty())
            assertEquals(0, control.diagnostics.state.value.pendingRequests)
        } finally {
            input.close()
            control.stop()
            scope.cancel()
            peer?.close(1000, null)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            server.close()
        }
    }
}
