package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.allBlocks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.ByteString
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Uses a temporary session it deletes afterwards, never one in use. Skipped unless REX_LIVE_URL is set. */
class LiveServerTest {
    private val ansi = Regex("\u001b\\[[0-9;?=]*[A-Za-z]")
    private val url = System.getenv("REX_LIVE_URL")

    @Test fun `bootstrap, create, stream, type, upload, destroy`() = runBlocking {
        assumeTrue("REX_LIVE_URL not set", !url.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
        val control = RexControl(http, url!!, { ClientInfo(version = "test", label = "hatch-live-test") }, scope)
        val sync = HostSync(Host(HostId("live"), "live", url), control, scope)
        var created: SessionId? = null
        sync.start()
        try {
            withTimeout(15_000) { sync.state.first { it.link == Link.Online } }
            println("bootstrapped: ${sync.state.value.sessions.size} sessions")
            sync.probe()
            assertTrue(control.diagnostics.state.value.port?.reachable == true)
            assertTrue(control.diagnostics.state.value.roundTrips.isNotEmpty())
            println("measured application RTT and TCP reachability")

            val session = sync.api.createSession("")
            created = session
            sync.api.setSessionLabel(session, "hatch-live-test")
            val block = withTimeout(15_000) {
                sync.state.first { s -> s.sessionsById[session]?.windows?.firstOrNull()?.let { s.windowsById[it] }?.allBlocks()?.isNotEmpty() == true }
                    .let { s -> s.windowsById[s.sessionsById[session]!!.windows.first()]!!.allBlocks().first() }
            }
            println("created session with one pane")

            val received = StringBuilder()
            val liveCount = MutableStateFlow(0)
            val paused = CompletableDeferred<Unit>()
            val stream = DataStream(http, url, DataStream.TERMINAL, { sync.api.terminalTicket(session, block) }, object : DataSink {
                override fun onStreamStart(nonce: Long) { synchronized(received) { received.clear() }; println("stream start"); }
                override fun onBytes(bytes: ByteString) { synchronized(received) { received.append(bytes.string(Charsets.ISO_8859_1)) } }
                override fun onState(state: DataStream.State) { if (state == DataStream.State.Live) liveCount.update { it + 1 }; println("stream $state") }
            }, scope, log = { message ->
                println(message)
                if (message.startsWith("data closed 1001")) paused.complete(Unit)
            }, diagnostics = control.diagnostics, streamId = block.value.takeLast(8))
            stream.start()
            withTimeout(15_000) { while (synchronized(received) { received.isEmpty() }) kotlinx.coroutines.delay(50) }
            println("snapshot bytes: ${received.length}")

            for (c in "printf 'hatch-%d\\n' 42") sync.api.key(session, block, KeyEvent(key = "unidentified", text = c.toString()))
            sync.api.key(session, block, KeyEvent(key = "enter"))
            val ran = kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!synchronized(received) { received.contains("hatch-42") }) kotlinx.coroutines.delay(50) }
            if (ran == null) {
                val tail = synchronized(received) { received.takeLast(700).toString() }
                println("TAIL:" + tail.map { if (it.code < 32 || it.code > 126) "\\x%02x".format(it.code) else it.toString() }.joinToString(""))
                error("typed command did not run")
            }
            println("typed command ran")
            control.reconnectNow()
            withTimeout(15_000) { control.diagnostics.state.first { it.controlResumes > 0 } }
            stream.pause()
            withTimeout(10_000) { paused.await() }
            stream.unpause()
            withTimeout(15_000) { liveCount.first { it >= 2 } }
            assertTrue(control.diagnostics.state.value.blockResumes > 0)
            println("measured control and terminal stream resumes")

            val bytes = "hello from the live test\n".toByteArray()
            Uploader(http, url, sync.api, log = ::println).upload(session, block, listOf(UploadSource("live-test.txt", bytes.size.toLong()) { bytes.inputStream() }))
            val typed = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (!synchronized(received) { received.replace(ansi, "").contains("live-test") }) kotlinx.coroutines.delay(50)
            }
            if (typed == null) {
                val tail = synchronized(received) { received.takeLast(900).toString() }
                println("TAIL:" + tail.map { if (it.code < 32 || it.code > 126) "\\x%02x".format(it.code) else it.toString() }.joinToString(""))
                error("upload path not typed")
            }
            println("upload typed its path")

            stream.close()
            sync.api.destroySession(session)
            withTimeout(10_000) { sync.state.first { session !in it.sessions } }
            created = null
            println("destroyed the temporary session")
        } finally {
            created?.let { runCatching { sync.api.destroySession(it) } }
            sync.stop()
            scope.cancel()
        }
    }
}
