package dev.mahali.hatch.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.protocol.ClientInfo
import dev.mahali.hatch.protocol.ControlStatus
import dev.mahali.hatch.protocol.DataSink
import dev.mahali.hatch.protocol.DataStream
import dev.mahali.hatch.protocol.RexApi
import dev.mahali.hatch.protocol.RexControl
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransportSpikeTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test fun readRangesThroughHelperTerminal() = runBlocking {
        val base = args.getString("base")
        val files = args.getString("files")
        assumeTrue("needs -e base and -e files", base != null && files != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()
        val control = RexControl(http, base!!, { ClientInfo(version = "spike", label = "preview-spike") }, scope)
        control.start()
        withTimeout(20_000) { control.status.first { it is ControlStatus.Open } }
        val api = RexApi(control)
        val session = control.call("session.create", buildJsonObject { put("label", "hatch-preview-spike") }).payload.str("session_id")
        control.call("session.attach", buildJsonObject { put("session_id", session) })
        try {
            for (entry in files!!.split(';')) {
                val (path, sha) = entry.split(':')
                Helper(control, api, http, base, scope, session, path).use { measure(it, path, sha) }
            }
        } finally {
            control.call("session.destroy", buildJsonObject { put("session_id", session) })
            control.stop()
            scope.cancel()
        }
    }

    private suspend fun measure(h: Helper, path: String, sha: String) {
        log("== ${path.substringAfterLast('/')}  ${h.size} bytes  spawn->META ${h.spawnMs} ms")
        var t = System.nanoTime()
        h.read(0, minOf(4096L, h.size))
        log("first 4 KiB: ${ms(t)} ms")
        var whole = ByteArray(0)
        for (chunk in listOf(256 shl 10, 1 shl 20, 4 shl 20)) {
            t = System.nanoTime()
            val out = java.io.ByteArrayOutputStream(h.size.toInt())
            var wire = 0L
            var off = 0L
            while (off < h.size) {
                val (bytes, w) = h.read(off, minOf(chunk.toLong(), h.size - off))
                out.write(bytes); wire += w; off += bytes.size
            }
            val secs = (System.nanoTime() - t) / 1e9
            whole = out.toByteArray()
            val got = MessageDigest.getInstance("SHA-256").digest(whole).joinToString("") { "%02x".format(it) }
            log("full read ${chunk shr 10} KiB ranges: %.2f s  %.2f MB/s payload  %.2f MB/s wire  sha256 %s"
                .format(secs, h.size / secs / 1e6, wire / secs / 1e6, if (got == sha) "ok" else "MISMATCH"))
            assertEquals(sha, got)
        }
        val lat = (1..10).map {
            val off = Random.nextLong(0, maxOf(1, h.size - 65536))
            val s = System.nanoTime()
            val (bytes, _) = h.read(off, minOf(65536L, h.size - off))
            val took = ms(s)
            assertEquals(true, bytes.contentEquals(whole.copyOfRange(off.toInt(), off.toInt() + bytes.size)))
            took
        }.sorted()
        log("seek 64 KiB x10: median ${lat[5]} ms, max ${lat.last()} ms (bytes ok)")
    }

    private class Helper(
        private val control: RexControl, api: RexApi, http: OkHttpClient, base: String,
        scope: CoroutineScope, private val session: String, path: String,
    ) : AutoCloseable {
        private val buf = StringBuilder() // latin-1, one char per byte
        private val lock = Any()
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private val live = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val block: String
        private val stream: DataStream
        val size: Long
        val spawnMs: Long

        init {
            val t = System.nanoTime()
            block = runBlocking {
                control.call("session.new_block", buildJsonObject {
                    put("session_id", session)
                    put("flavor", "com.superlogical.terminal.shell")
                    put("label", "hatch-preview")
                    put("options", buildJsonObject {
                        put("shell", "none")
                        put("command", buildJsonArray { listOf("/bin/sh", "-c", HELPER, "hatch", path).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    })
                }).payload.str("block_id")
            }
            stream = DataStream(http, base, DataStream.TERMINAL, { api.terminalTicket(SessionId(session), BlockId(block)) }, object : DataSink {
                override fun onStreamStart(nonce: Long) { log("stream start") }
                override fun onBytes(bytes: ByteString) {
                    synchronized(lock) { buf.append(String(bytes.toByteArray(), Charsets.ISO_8859_1)) }
                    wake.trySend(Unit)
                }
                override fun onState(state: DataStream.State) {
                    log("stream $state")
                    if (state == DataStream.State.Live) live.complete(Unit)
                }
            }, scope)
            stream.start()
            val meta = runBlocking {
                withTimeout(20_000) { live.await() } // the helper prints nothing until "go", so no bytes are missed
                write("go\n")
                waitFor(Regex("""HATCH-(META (\d+)|ERR \w+)\r?\n"""))
            }
            spawnMs = (System.nanoTime() - t) / 1_000_000
            size = meta.groups[2].toLongOrNull() ?: error(meta.groups[0])
            consume(meta.next)
        }

        suspend fun read(off: Long, len: Long): Pair<ByteArray, Int> {
            write("$off $len\n")
            val begin = waitFor(Regex("""HATCH-BEGIN $off $len\r?\n"""))
            val end = waitFor(Regex("""\r?\nHATCH-END $off\r?\n"""), begin.next)
            val raw = synchronized(lock) { buf.substring(begin.next, end.first) }
            consume(end.next)
            // ponytail: keeps the server's helper scrollback from growing with every range
            control.call("com.superlogical.terminal.clear", terminalArgs())
            return Base64.getMimeDecoder().decode(raw.filter { !it.isWhitespace() }) to raw.length
        }

        private fun consume(upTo: Int) = synchronized(lock) { buf.delete(0, upTo) }

        private suspend fun write(text: String) {
            control.call("com.superlogical.terminal.write", terminalArgs { put("data", text) })
        }

        private fun terminalArgs(args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
            put("session_id", session); put("block_id", block); put("args", buildJsonObject(args))
        }

        class Found(val first: Int, val next: Int, val groups: List<String>)

        /** Matches only new bytes: Android's Matcher copies its whole input, so matching the growing buffer is quadratic. */
        private suspend fun waitFor(rx: Regex, from: Int = 0): Found = withTimeout(120_000) {
            var scanFrom = from
            while (true) {
                synchronized(lock) {
                    rx.find(buf.substring(scanFrom))?.let { m ->
                        return@withTimeout Found(scanFrom + m.range.first, scanFrom + m.range.last + 1, m.groupValues)
                    }
                    scanFrom = maxOf(from, buf.length - 64)
                }
                wake.receive()
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

        override fun close() {
            stream.close()
            runBlocking { runCatching { control.call("block.close", buildJsonObject { put("session_id", session); put("block_id", block) }) } }
        }
    }

    private companion object {
        // Fixed script; the path only ever arrives as $1.
        const val HELPER = """
stty -echo 2>/dev/null
f="${'$'}1"
IFS= read -r _go
[ -f "${'$'}f" ] || { printf 'HATCH-ERR notfile\n'; exit 2; }
printf 'HATCH-META %s\n' "${'$'}(wc -c < "${'$'}f" | tr -d ' ')"
while IFS=' ' read -r off len; do
  case "${'$'}off${'$'}len" in ''|*[!0-9]*) printf 'HATCH-ERR bad\n'; continue;; esac
  printf 'HATCH-BEGIN %s %s\n' "${'$'}off" "${'$'}len"
  tail -c +${'$'}((off+1)) -- "${'$'}f" | head -c "${'$'}len" | base64
  printf '\nHATCH-END %s\n' "${'$'}off"
done
"""

        fun log(s: String) { Log.i("HatchSpike", s) }
        fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
        fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    }
}
