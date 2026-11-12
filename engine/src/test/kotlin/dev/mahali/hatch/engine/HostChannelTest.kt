package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostFailure
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.protocol.ClientInfo
import dev.mahali.hatch.protocol.Control
import dev.mahali.hatch.protocol.ControlStatus
import dev.mahali.hatch.protocol.Envelope
import dev.mahali.hatch.protocol.KeyEvent
import dev.mahali.hatch.protocol.Limits
import dev.mahali.hatch.protocol.RexApi
import dev.mahali.hatch.protocol.RexControl
import dev.mahali.hatch.protocol.RexError
import dev.mahali.hatch.protocol.decodeEnvelope
import dev.mahali.hatch.protocol.encode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostChannelTest {
    private val session = SessionId("s")
    private val a = BlockId("a")
    private val b = BlockId("b")
    private val key = KeyEvent(key = "a", text = "a")
    private fun size(cols: Int) = GridSize(cols, 24, 10, 20)

    private fun CommandSubmission.accepted() = (this as CommandSubmission.Accepted).receipt

    @Test fun `unconfined resize completion cannot leave a pending receipt or phantom waiting resize`() = runTest {
        for (dispatcher in listOf(UnconfinedTestDispatcher(testScheduler), Dispatchers.Unconfined)) {
            val fake = Fake()
            val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
            val channel = fake.channel(OrderedInput(scope, {}))
            val first = channel.send(Command.Control.Resize(session, mapOf(a to size(80)))).accepted()
            assertTrue(first.isCompleted)
            assertEquals(CommandOutcome.Acknowledged, first.await())
            val second = channel.send(Command.Control.Resize(session, mapOf(a to size(90)))).accepted()
            assertTrue(second.isCompleted)
            assertEquals(CommandOutcome.Acknowledged, second.await())
            assertEquals(listOf(
                Command.Control.Resize(session, mapOf(a to size(80))),
                Command.Control.Resize(session, mapOf(a to size(90))),
            ), fake.sent)
        }
    }

    @Test fun `unconfined rejected partial resize restores geometry and original receipt ownership`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val input = OrderedInput(scope, {}, maxBatches = 2, maxInFlight = 1)
        val gate = CompletableDeferred<Unit>()
        assertTrue(input.submit(0, sequenceOf({ gate.await() }, {})))
        val fake = Fake()
        val channel = fake.channel(input)
        val original = channel.send(Command.Control.Resize(session, mapOf(a to size(80)))).accepted()
        assertTrue(!original.isCompleted)
        assertEquals(CommandSubmission.Rejected(AdmissionFailure.Capacity),
            channel.send(Command.Control.Resize(session, mapOf(a to size(90), b to size(40)))))
        assertTrue(!original.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(original.isCompleted)
        assertEquals(CommandOutcome.Acknowledged, original.await())
        assertEquals(listOf(Command.Control.Resize(session, mapOf(a to size(80)))), fake.sent)
    }

    @Test fun `admission distinguishes offline capacity and closed`() = runTest {
        val fake = Fake()
        val input = OrderedInput(backgroundScope, {}, maxBatches = 1)
        val channel = fake.channel(input)
        fake.online = false
        assertEquals(CommandSubmission.Rejected(AdmissionFailure.Disconnected), channel.send(Command.Input.Key(session, a, key)))
        fake.online = true
        val first = channel.send(Command.Input.Key(session, a, key)).accepted()
        assertEquals(CommandSubmission.Rejected(AdmissionFailure.Capacity), channel.send(Command.Input.Key(session, a, key)))
        input.close()
        runCurrent()
        assertEquals(CommandOutcome.Cancelled, first.await())
        assertEquals(CommandSubmission.Rejected(AdmissionFailure.Closed), channel.send(Command.Input.Key(session, a, key)))
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun `receipt awaits all replies and known failure survives partial success`() = runTest {
        val reply = CompletableDeferred<Unit>()
        val channel = HostChannel("host", OrderedInput(backgroundScope, {}), { true }, {
            sequenceOf({ reply.await() }, { throw RexError("bad_request", "no") })
        }, {})
        val receipt = channel.send(Command.Input.Text(session, a, "ab")).accepted()
        runCurrent()
        assertTrue(!receipt.isCompleted)
        reply.complete(Unit)
        runCurrent()
        assertEquals(CommandOutcome.Failed(HostFailure.Rejected("bad_request", "no"), false), receipt.await())
    }

    @Test fun `success acknowledges RPC and transport failures remain uncertain`() = runTest {
        for ((error, expected) in listOf(
            null to CommandOutcome.Acknowledged,
            RexError("timeout", "late") to CommandOutcome.Failed(HostFailure.Timeout, true),
            RexError("disconnected", "lost") to CommandOutcome.Failed(HostFailure.Disconnected, true),
            RexError("not_found", "gone") to CommandOutcome.Failed(HostFailure.Gone, false),
        )) {
            val fake = Fake(if (error == null) emptyMap() else mapOf(Command.Input.Key::class.java to error))
            val receipt = fake.channel(OrderedInput(backgroundScope, {})).send(Command.Input.Key(session, a, key)).accepted()
            runCurrent()
            assertEquals(expected, receipt.await())
        }
    }

    @Test fun `close settles in flight and undispatched receipts without replay`() = runTest {
        val input = OrderedInput(backgroundScope, {}, maxInFlight = 1)
        var sent = 0
        val channel = HostChannel("host", input, { true }, {
            sequenceOf({ sent++; CompletableDeferred<Unit>().await() })
        }, {})
        val receipts = List(3) { channel.send(Command.Input.Key(session, a, key)).accepted() }
        runCurrent()
        assertEquals(1, sent)
        input.close()
        runCurrent()
        receipts.forEach { assertEquals(CommandOutcome.Cancelled, it.await()) }
        assertEquals(1, sent)
    }

    @Test fun `partially folded resize supersedes old request and waits for every actual task`() = runTest {
        val replies = mutableMapOf<BlockId, CompletableDeferred<Unit>>()
        val sent = mutableListOf<Command>()
        val channel = HostChannel("host", OrderedInput(backgroundScope, {}), { true }, { command ->
            sequenceOf({
                sent += command
                if (command is Command.Control.Resize) {
                    val pane = command.sizes.keys.single()
                    replies.getOrPut(pane) { CompletableDeferred() }.await()
                }
            })
        }, {})
        val old = channel.send(Command.Control.Resize(session, mapOf(a to size(80)))).accepted()
        channel.send(Command.Input.Key(session, a, key))
        val replacement = channel.send(Command.Control.Resize(session, mapOf(a to size(90), b to size(40)))).accepted()
        assertEquals(CommandOutcome.Superseded, old.await())
        runCurrent()
        assertEquals(listOf(
            Command.Control.Resize(session, mapOf(a to size(90))),
            Command.Input.Key(session, a, key),
            Command.Control.Resize(session, mapOf(b to size(40))),
        ), sent)
        replies.getValue(a).complete(Unit)
        runCurrent()
        assertTrue(!replacement.isCompleted)
        replies.getValue(b).complete(Unit)
        runCurrent()
        assertEquals(CommandOutcome.Acknowledged, replacement.await())
    }

    @Test fun `resize spanning two earlier batches retains failure until both finish`() = runTest {
        val reply = CompletableDeferred<Unit>()
        val channel = HostChannel("host", OrderedInput(backgroundScope, {}), { true }, { command ->
            sequenceOf({
                val resize = command as Command.Control.Resize
                if (a in resize.sizes) throw RexError("timeout", "late")
                reply.await()
            })
        }, {})
        val oldA = channel.send(Command.Control.Resize(session, mapOf(a to size(80)))).accepted()
        val oldB = channel.send(Command.Control.Resize(session, mapOf(b to size(80)))).accepted()
        val replacement = channel.send(Command.Control.Resize(session, mapOf(a to size(90), b to size(90)))).accepted()
        assertEquals(CommandOutcome.Superseded, oldA.await())
        assertEquals(CommandOutcome.Superseded, oldB.await())
        runCurrent()
        assertTrue(!replacement.isCompleted)
        reply.complete(Unit)
        runCurrent()
        assertEquals(CommandOutcome.Failed(HostFailure.Timeout, true), replacement.await())
    }

    @Test fun `repeated coalescing settles every replaced receipt`() = runTest {
        val fake = Fake()
        val channel = fake.channel(OrderedInput(backgroundScope, {}, maxBatches = 1))
        val receipts = List(1000) { i ->
            channel.send(Command.Control.Resize(session, mapOf(a to size(i + 1)))).accepted()
        }
        receipts.dropLast(1).forEach { assertEquals(CommandOutcome.Superseded, it.await()) }
        runCurrent()
        assertEquals(CommandOutcome.Acknowledged, receipts.last().await())
        assertEquals(listOf(Command.Control.Resize(session, mapOf(a to size(1000)))), fake.sent)
    }

    private class Fake(val errors: Map<Class<*>, Exception> = emptyMap()) {
        val sent = mutableListOf<Command>()
        val reported = mutableListOf<String>()
        var online = true
        fun channel(input: OrderedInput) = HostChannel("host", input, { online }, { command ->
            sequenceOf(suspend { sent += command; errors[command.javaClass]?.let { throw it } })
        }, { reported += it })
    }

    @Test fun `a key sent after a three-pane resize is written after all three resizes`() = runBlocking {
        val requests = LinkedBlockingQueue<Envelope.Request>()
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
        val input = OrderedInput(scope, { throw AssertionError(it) })
        val reported = mutableListOf<String>()
        val channel = HostChannel("host", input, { true }, RexApi(control)::calls, { reported += it })
        try {
            control.start()
            withTimeout(5000) { control.status.first { it is ControlStatus.Open } }
            val panes = listOf("p1", "p2", "p3").map(::BlockId)
            channel.send(Command.Control.Resize(session, panes.associateWith { size(80) }))
            channel.send(Command.Input.Key(session, panes[0], key))
            val written = List(4) { requests.poll(5, TimeUnit.SECONDS) ?: error("Request did not reach socket") }
            assertEquals(List(3) { "com.superlogical.terminal.resize" } + "client.key", written.map { it.method })
            assertEquals(panes.map { it.value }, written.take(3).map { it.payload.getValue("block_id").jsonPrimitive.content })
            assertTrue(reported.isEmpty())
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

    @Test fun `input failure is reported, control failure is not, gone is silent for both`() = runTest {
        val failing = Fake(mapOf(
            Command.Input.Key::class.java to RexError("bad_request", "no"),
            Command.Control.Resize::class.java to RexError("bad_request", "no"),
        ))
        failing.channel(OrderedInput(backgroundScope, { throw AssertionError(it) })).apply {
            send(Command.Control.Resize(session, mapOf(a to size(80))))
            send(Command.Input.Key(session, a, key))
        }
        runCurrent()
        assertEquals(2, failing.sent.size)
        assertEquals(listOf("host: input delivery failed. Some input may not have reached the terminal."), failing.reported)

        val gone = Fake(mapOf(
            Command.Input.Paste::class.java to RexError("not_found", "gone"),
            Command.Control.Focus::class.java to RexError("session_not_found", "gone"),
        ))
        gone.channel(OrderedInput(backgroundScope, { throw AssertionError(it) })).apply {
            send(Command.Input.Paste(session, a, "x"))
            send(Command.Control.Focus(session, a, true))
        }
        runCurrent()
        assertEquals(2, gone.sent.size)
        assertTrue(gone.reported.isEmpty())
    }

    @Test fun `resizes coalesce to the latest per pane without moving after later input`() = runTest {
        val fake = Fake()
        val channel = fake.channel(OrderedInput(backgroundScope, { throw AssertionError(it) }))
        val theme = TerminalColors(0xFFFFFF, 0, 0xFFFFFF, List(16) { it }, dark = true)
        channel.send(Command.Control.Resize(session, mapOf(a to size(80)), theme))
        channel.send(Command.Input.Key(session, a, key))
        channel.send(Command.Control.Resize(session, mapOf(a to size(90), b to size(40))))
        runCurrent()
        channel.send(Command.Control.Resize(session, mapOf(a to size(100))))
        runCurrent()
        assertEquals(listOf(
            Command.Control.Resize(session, mapOf(a to size(90)), theme),
            Command.Input.Key(session, a, key),
            Command.Control.Resize(session, mapOf(b to size(40))),
            Command.Control.Resize(session, mapOf(a to size(100))),
        ), fake.sent)
    }

    @Test fun `full queue and disconnection report input only, and control skips the byte budget`() = runTest {
        val fake = Fake()
        val channel = fake.channel(OrderedInput(backgroundScope, { throw AssertionError(it) }, maxBatches = 2, maxBytes = 200))
        channel.send(Command.Input.Paste(session, a, "x".repeat(30)))
        channel.send(Command.Control.Focus(session, a, true))
        channel.send(Command.Input.Key(session, a, key))
        channel.send(Command.Control.Focus(session, a, false))
        assertEquals(listOf("host: input queue is full. This input wasn't sent; wait for the host before trying again."), fake.reported)
        fake.online = false
        channel.send(Command.Input.Key(session, a, key))
        channel.send(Command.Control.Release(session, setOf(a)))
        assertEquals("Input wasn't sent because the host is disconnected.", fake.reported.last())
        assertEquals(2, fake.reported.size)
        runCurrent()
        assertEquals(listOf(Command.Input.Paste(session, a, "x".repeat(30)), Command.Control.Focus(session, a, true)), fake.sent)
    }
}
