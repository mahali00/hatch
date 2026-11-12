package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Rect
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.engine.createTabOperations
import dev.mahali.hatch.protocol.ClientInfo
import dev.mahali.hatch.protocol.Control
import dev.mahali.hatch.protocol.ControlStatus
import dev.mahali.hatch.protocol.Envelope
import dev.mahali.hatch.protocol.ErrorInfo
import dev.mahali.hatch.protocol.Limits
import dev.mahali.hatch.protocol.RexApi
import dev.mahali.hatch.protocol.RexControl
import dev.mahali.hatch.protocol.decodeEnvelope
import dev.mahali.hatch.protocol.encode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabCommandTest {
    private val host = HostId("host")
    private val session = SessionId("session")
    private val window = WindowId("window")
    private val pane = BlockId("pane")
    private val target = WindowRef(host, session, window)

    private class Events {
        val requests = Channel<Envelope.Request>(Channel.UNLIMITED)
        val opened = Channel<WindowRef>(Channel.UNLIMITED)
        val failures = Channel<String>(Channel.UNLIMITED)
        val splits = Channel<BlockId>(Channel.UNLIMITED)
        val commandHosts = Channel<HostId>(Channel.UNLIMITED)
    }

    private fun connected(
        reply: (Envelope.Request) -> Envelope.Response?,
        test: suspend (TabController, Events, Job) -> Unit,
    ) = runBlocking {
        val events = Events()
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = decodeEnvelope(text)) {
                    is Envelope.Hello -> webSocket.send(
                        Envelope.Welcome(Control.SUBPROTOCOL, Limits(1_048_576)).encode(),
                    )
                    is Envelope.Request -> {
                        events.requests.trySend(message)
                        reply(message)?.let { webSocket.send(it.encode()) }
                    }
                    else -> Unit
                }
            }
        }).build())
        server.start()
        val networkScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val tabJob = SupervisorJob(coroutineContext[Job])
        val tabScope = CoroutineScope(coroutineContext + tabJob)
        val http = OkHttpClient()
        val control = RexControl(
            http, server.url("/").toString(),
            { ClientInfo(version = "test", label = "hatch-tab-test") }, networkScope,
        )
        try {
            control.start()
            withTimeout(5_000) { control.status.first { it is ControlStatus.Open } }
            val state = HostState(
                Host(host, "Test", server.url("/").toString()),
                link = Link.Online,
                sessionsById = mapOf(session to Session(session, "", revision = 1, windows = listOf(window))),
                windowsById = mapOf(window to Window(window, "", Layout.Leaf(pane), focused = pane)),
            )
            val api = RexApi(control)
            val tab = TabController(
                target, false, MutableStateFlow(AppState(listOf(host), mapOf(host to state))), tabScope,
                operations = createTabOperations(target, api = { requestedHost ->
                    events.commandHosts.trySend(requestedHost)
                    api
                }, sync = { null }, startup = { "" }),
                say = { events.failures.trySend(it) },
                onOpenTab = { events.opened.trySend(it) }, onBack = {}, onSplit = { events.splits.trySend(it) },
                onTabGone = { _, _ -> }, onNewTab = {},
            )
            withTimeout(5_000) { test(tab, events, tabJob) }
        } finally {
            tabScope.cancel()
            control.stop()
            networkScope.cancel()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            server.close()
        }
    }

    @Test fun `split requests the server returned pane after success`() = connected(
        reply = { request ->
            Envelope.Response(request.id, payload = buildJsonObject { put("block_ids", buildJsonArray { add("created") }) })
        },
    ) { tab, events, _ ->
        tab.split(pane, below = false)

        assertEquals("session.new_split", events.requests.receive().method)
        assertEquals(BlockId("created"), events.splits.receive())
        assertEquals(BlockId("created"), tab.pending)
        assertTrue(events.failures.tryReceive().isFailure)
    }

    @Test fun `a rejected split reports once without navigation or retry`() = connected(
        reply = { request -> Envelope.Response(request.id, error = ErrorInfo("rejected", "Creation refused")) },
    ) { tab, events, _ ->
        tab.split(pane, below = false)

        assertEquals("session.new_split", events.requests.receive().method)
        assertEquals("Could not split pane: Creation refused", events.failures.receive())
        assertTrue(events.opened.tryReceive().isFailure)
        assertTrue(events.requests.tryReceive().isFailure)
        assertTrue(events.failures.tryReceive().isFailure)
    }

    @Test fun `server focus rejection does not undo local navigation`() = connected(
        reply = { request -> Envelope.Response(request.id, error = ErrorInfo("not_found", "Tab no longer exists")) },
    ) { tab, events, _ ->
        val next = WindowRef(host, session, WindowId("next"))
        tab.openTab(next)

        assertEquals(next, events.opened.receive())
        assertEquals("session.focus_window", events.requests.receive().method)
        assertEquals("Could not focus tab: Tab no longer exists", events.failures.receive())
        assertTrue(events.opened.tryReceive().isFailure)
    }

    @Test fun `layout and close commands stay bound to the source session`() = connected(
        reply = { request -> Envelope.Response(request.id, payload = buildJsonObject {}) },
    ) { tab, events, _ ->
        val other = BlockId("other")
        tab.resize(PaneDivider("", Rect.Full, Axis.Column, 0.5f, pane, other), 0.6f)
        val resize = events.requests.receive()
        assertEquals("session.resize_pane", resize.method)
        assertEquals("vertical", resize.payload["direction"]?.jsonPrimitive?.content)

        tab.drop(pane, other, DropZone.Top)
        val move = events.requests.receive()
        assertEquals("session.move_block", move.method)
        assertEquals("vertical", move.payload["direction"]?.jsonPrimitive?.content)
        assertEquals("before", move.payload["side"]?.jsonPrimitive?.content)

        tab.drop(pane, other, DropZone.Centre)
        val swap = events.requests.receive()
        assertEquals("session.swap_blocks", swap.method)

        tab.close(pane)
        val close = events.requests.receive()
        assertEquals("block.close", close.method)
        for (request in listOf(resize, move, swap, close)) {
            assertEquals(session.value, request.payload["session_id"]?.jsonPrimitive?.content)
            assertEquals(host, events.commandHosts.receive())
        }
    }

    @Test fun `focus other tab alone may address another host and session`() = connected(
        reply = { request -> Envelope.Response(request.id, payload = buildJsonObject {}) },
    ) { tab, events, _ ->
        val destination = WindowRef(HostId("other-host"), SessionId("other-session"), WindowId("other-window"))
        tab.openTab(destination)
        assertEquals(destination, events.opened.receive())
        val request = events.requests.receive()
        assertEquals("session.focus_window", request.method)
        assertEquals(destination.host, events.commandHosts.receive())
        assertEquals(destination.session.value, request.payload["session_id"]?.jsonPrimitive?.content)
        assertEquals(destination.window.value, request.payload["window_id"]?.jsonPrimitive?.content)
    }

    @Test fun `leaving cancels a pending split without reporting a server failure`() = connected(
        reply = { null },
    ) { tab, events, tabJob ->
        tab.split(pane, below = false)
        assertEquals("session.new_split", events.requests.receive().method)

        tabJob.cancelAndJoin()

        assertTrue(events.opened.tryReceive().isFailure)
        assertTrue(events.splits.tryReceive().isFailure)
        assertTrue(events.failures.tryReceive().isFailure)
        assertTrue(events.requests.tryReceive().isFailure)
    }
}
