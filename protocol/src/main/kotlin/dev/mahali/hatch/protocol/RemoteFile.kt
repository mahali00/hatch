package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.ByteString

/**
 * Rex has no file read method, so reads run through a detached helper terminal with a fixed script.
 * The path reaches the script only as `$1`, never as shell text. The server keeps terminal scrollback, so the helper is replaced after [RESTART_BYTES].
 */
public class RemoteFile private constructor(
    private val api: RexApi,
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val session: SessionId,
    private val path: String,
    private val scope: CoroutineScope,
    private val label: String,
) {
    private val reads = Mutex()
    private var helper: Helper? = null
    private var closed = false

    public var size: Long = 0
        private set

    public suspend fun read(offset: Long, length: Int): ByteArray = reads.withLock {
        if (closed) throw RexError("closed", "The preview is closed")
        require(offset >= 0 && length >= 0)
        val current = helper?.takeIf { it.output.received < RESTART_BYTES } ?: run {
            helper?.dispose()
            helper = null
            Helper.start(api, http, baseUrl, session, path, scope, label).also {
                if (it.size != size) { it.dispose(); throw RexError("changed", "The file changed") }
                helper = it
            }
        }
        current.range(offset, length)
    }

    public suspend fun dispose(): Unit = reads.withLock {
        if (closed) return@withLock
        closed = true
        val previous = helper
        helper = null
        withContext(NonCancellable) { previous?.dispose() }
    }

    private class Helper(private val api: RexApi, private val session: SessionId, val block: BlockId) {
        val output = HelperOutput()
        private val live = CompletableDeferred<Unit>()
        private var stream: DataStream? = null
        var size = 0L
            private set

        suspend fun range(offset: Long, length: Int): ByteArray {
            write("$offset $length\n")
            return output.range(offset, length)
        }

        suspend fun dispose() {
            stream?.close()
            inUse.remove(block)
            runCatching { api.closeBlock(session, block) }
        }

        private suspend fun write(text: String) = api.write(session, block, text)

        companion object {
            suspend fun start(
                api: RexApi, http: OkHttpClient, baseUrl: String, session: SessionId, path: String, scope: CoroutineScope, label: String,
            ): Helper {
                val helper = helpers.withLock {
                    Helper(api, session, api.newDetachedTerminal(session, label, listOf("/bin/sh", "-c", SCRIPT, "hatch", path)))
                        .also { inUse.add(it.block) }
                }
                try {
                    helper.stream = DataStream(http, baseUrl, DataStream.TERMINAL, { api.terminalTicket(session, helper.block) }, object : DataSink {
                        override fun onStreamStart(nonce: Long) {}
                        override fun onBytes(bytes: ByteString) = helper.output.append(bytes.toByteArray())
                        override fun onState(state: DataStream.State) {
                            if (state == DataStream.State.Live) helper.live.complete(Unit)
                        }
                    }, scope).also { it.start() }
                    // The script prints nothing until "go", so no output precedes the live stream.
                    withTimeout(TIMEOUT_MILLIS) { helper.live.await() }
                    helper.write("go\n")
                    helper.size = helper.output.meta()
                    return helper
                } catch (e: Throwable) {
                    withContext(NonCancellable) { helper.dispose() }
                    throw e
                }
            }
        }
    }

    public companion object {
        internal const val RESTART_BYTES: Long = 64L shl 20
        internal const val TIMEOUT_MILLIS: Long = 30_000
        private const val LABEL = "hatch-preview"

        // Helper blocks this process has open; a sweep must never close one in use.
        private val inUse = ConcurrentHashMap.newKeySet<BlockId>()
        private val helpers = Mutex()

        private val SCRIPT = """
            stty -echo 2>/dev/null
            f="${'$'}1"
            case "${'$'}f" in "~/"*) f="${'$'}HOME/${'$'}{f#\~/}";; esac
            IFS= read -r _go
            [ -f "${'$'}f" ] || { printf 'HATCH-ERR notfile\n'; exit 2; }
            printf 'HATCH-META %s\n' "${'$'}(wc -c < "${'$'}f" | tr -d ' ')"
            while IFS=' ' read -r off len; do
              case "${'$'}off${'$'}len" in ''|*[!0-9]*) printf 'HATCH-ERR bad\n'; continue;; esac
              printf 'HATCH-BEGIN %s %s\n' "${'$'}off" "${'$'}len"
              tail -c +${'$'}((off+1)) -- "${'$'}f" | head -c "${'$'}len" | base64
              printf '\nHATCH-END %s\n' "${'$'}off"
            done
        """.trimIndent()

        // The helper label includes [owner], so a sweep closes only this installation's leftover helpers.
        public suspend fun open(
            api: RexApi, http: OkHttpClient, baseUrl: String, session: SessionId, path: String, scope: CoroutineScope, owner: UUID,
        ): RemoteFile {
            if (api.serverVersion().str("platform").orEmpty().startsWith("windows")) {
                throw RexError("not_supported", "Previews need a macOS or Linux server")
            }
            val label = "$LABEL $owner"
            sweep(api, session, label)
            val file = RemoteFile(api, http, baseUrl, session, path, scope, label)
            val helper = Helper.start(api, http, baseUrl, session, path, scope, label)
            file.helper = helper
            file.size = helper.size
            return file
        }

        private suspend fun sweep(api: RexApi, session: SessionId, label: String) = helpers.withLock {
            val blocks = runCatching { api.listBlocks(session) }.getOrNull() ?: return@withLock
            for (b in blocks) if (b.label == label && b.detached && b.id !in inUse) runCatching { api.closeBlock(session, b.id) }
        }
    }
}

/** Appended on the network thread; read by one caller at a time. */
internal class HelperOutput {
    private val gate = Any()
    private var buf = ByteArray(1 shl 16)
    private var len = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile var received: Long = 0
        private set

    fun append(bytes: ByteArray) {
        synchronized(gate) {
            if (len + bytes.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + bytes.size))
            bytes.copyInto(buf, len)
            len += bytes.size
            received += bytes.size
        }
        wake.trySend(Unit)
    }

    suspend fun meta(): Long {
        val start = find(HATCH, 0) + HATCH.size
        val eol = find(NEWLINE, start)
        val line = synchronized(gate) { buf.decodeToString(start, eol) }.trim()
        consume(eol + 1)
        return line.removePrefix("META ").toLongOrNull() ?: throw RexError("not_found", "Not a file")
    }

    suspend fun range(offset: Long, length: Int): ByteArray {
        val begin = "HATCH-BEGIN $offset $length".encodeToByteArray()
        val start = find(begin, 0) + begin.size
        val end = find("\nHATCH-END $offset".encodeToByteArray(), start)
        // Terminal output has \r\n line breaks; the MIME decoder skips them.
        val bytes = synchronized(gate) { Base64.getMimeDecoder().decode(buf.copyOfRange(start, end)) }
        consume(find(NEWLINE, end + 1) + 1)
        return bytes
    }

    private suspend fun find(pattern: ByteArray, from: Int): Int = withTimeout(RemoteFile.TIMEOUT_MILLIS) {
        var scan = from
        while (true) {
            synchronized(gate) {
                val at = indexOf(pattern, scan)
                if (at >= 0) return@withTimeout at
                scan = maxOf(from, len - pattern.size + 1)
            }
            wake.receive()
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    private fun indexOf(pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..len - pattern.size) {
            for (j in pattern.indices) if (buf[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun consume(upTo: Int) = synchronized(gate) {
        buf.copyInto(buf, 0, upTo, len)
        len -= upTo
    }

    private companion object {
        val HATCH = "HATCH-".encodeToByteArray()
        val NEWLINE = "\n".encodeToByteArray()
    }
}
