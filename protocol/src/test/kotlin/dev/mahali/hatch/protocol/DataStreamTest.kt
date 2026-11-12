package dev.mahali.hatch.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataStreamTest {
    @Test fun `bytes from a replaced generation are not delivered and cannot move the offset`() {
        val stream = stream()
        try {
            val first = stream.retire()
            assertTrue(stream.noteWelcome(first))
            assertTrue(stream.takeBytes(first, 8) {})

            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val delivering = Thread {
                stream.takeBytes(first, 4) {
                    inside.countDown()
                    release.await()
                }
            }
            delivering.start()
            assertTrue(inside.await(2, TimeUnit.SECONDS))
            val replaced = AtomicBoolean(false)
            val replacer = Thread {
                stream.retire()
                replaced.set(true)
            }
            replacer.start()
            Thread.sleep(80)
            assertFalse(replaced.get())
            release.countDown()
            replacer.join(2_000)
            delivering.join(2_000)
            assertFalse(replacer.isAlive)
            assertFalse(delivering.isAlive)
            assertTrue(replaced.get())
            assertEquals(12L, stream.deliveredBytes())

            var delivered = false
            assertFalse(stream.takeBytes(first, 100) { delivered = true })
            assertFalse(delivered)
            assertEquals(12L, stream.deliveredBytes())
        } finally {
            stream.close()
        }
    }

    @Test fun `resume offset is the bytes delivered on the current socket`() = runBlocking {
        val handshakes = Channel<Pair<WebSocket, ByteString>>(4)
        val server = MockWebServer()
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repeat(3) {
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handshakes.trySend(webSocket to bytes) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }).build())
        }
        server.start()
        val received = java.util.concurrent.atomic.AtomicInteger()
        val stream = DataStream(http, server.url("/").toString(), DataStream.TERMINAL, {
            Ticket(ByteArray(16) { it.toByte() }, 60_000)
        }, object : DataSink {
            override fun onStreamStart(nonce: Long) {}
            override fun onBytes(bytes: ByteString) { received.addAndGet(bytes.size) }
            override fun onState(state: DataStream.State) {}
        }, scope)
        try {
            stream.start()
            val (first, hello) = withTimeout(5_000) { handshakes.receive() }
            assertEquals(DataStream.HELLO, hello[0])
            first.send(welcome())
            first.send(ByteArray(8) { (it + 1).toByte() }.toByteString())
            withTimeout(5_000) { while (received.get() < 8) kotlinx.coroutines.delay(10) }
            stream.retryNow()
            val (_, resume) = withTimeout(5_000) { handshakes.receive() }
            assertEquals(8L, resumeOffset(resume))
            runCatching { first.send(ByteArray(100) { 9.toByte() }.toByteString()) }
            stream.retryNow()
            val (_, again) = withTimeout(5_000) { handshakes.receive() }
            assertEquals(8L, resumeOffset(again))
        } finally {
            stream.close()
            scope.cancel()
            server.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    private fun stream(): DataStream {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return DataStream(OkHttpClient(), "http://127.0.0.1:9", DataStream.TERMINAL, { error("no ticket") }, object : DataSink {
            override fun onStreamStart(nonce: Long) {}
            override fun onBytes(bytes: ByteString) {}
            override fun onState(state: DataStream.State) {}
        }, scope)
    }

    private fun welcome(): ByteString {
        val bytes = ByteArray(32)
        bytes[0] = 0x81.toByte()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(24, 7L)
        return bytes.toByteString()
    }

    private fun resumeOffset(bytes: ByteString): Long {
        assertEquals(25, bytes.size)
        assertEquals(DataStream.RESUME, bytes[0])
        return ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getLong(17)
    }
}
