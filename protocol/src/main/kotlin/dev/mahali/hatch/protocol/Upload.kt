package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.util.UUID

public class UploadSource(public val name: String, public val size: Long, public val open: () -> InputStream)

/** The server types the paths into the terminal once the whole batch has arrived. */
public class Uploader(
    private val http: OkHttpClient,
    baseUrl: String,
    private val api: RexApi,
    private val log: (String) -> Unit = {},
    private val timeoutMillis: Long = 30_000,
) {
    private val url = baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/data"

    /** Throws [RexError] when the server refuses or a stream does not end with `ok`; then nothing was typed. */
    public suspend fun upload(
        session: SessionId,
        block: BlockId,
        files: List<UploadSource>,
        progress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        require(files.isNotEmpty())
        val total = files.sumOf { it.size }
        val batchId = if (files.size > 1) UUID.randomUUID().toString() else null
        var sent = 0L
        // Send every file before waiting: the server ends each stream only after the whole batch arrives.
        val sockets = mutableListOf<WebSocket>()
        try {
            val endings = files.mapIndexed { index, file ->
                val ticket = api.uploadBegin(
                    session, block, "upload:${UUID.randomUUID()}", file.name, file.size,
                    batchId?.let { RexApi.Batch(it, index, files.size) },
                )
                send(ticket, file, sockets) { n -> sent += n; progress(sent, total) }
            }
            withTimeoutOrNull(timeoutMillis) { endings.awaitAll() }
                ?: throw RexError("timeout", "The upload did not finish in time")
        } finally { sockets.forEach { it.cancel() } }
    }

    private suspend fun send(ticket: Ticket, file: UploadSource, sockets: MutableList<WebSocket>, sentBytes: (Int) -> Unit): CompletableDeferred<Unit> {
        val welcomed = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", DataStream.BYTES).build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send((byteArrayOf(DataStream.HELLO) + ticket.bytes).toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!welcomed.isCompleted) {
                    if (parseWelcome(bytes) != null) welcomed.complete(Unit)
                    else welcomed.completeExceptionally(RexError("rejected", "The server refused the upload (${bytes.getOrNull(1)})"))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("upload ${file.name} closed by server: $code '$reason'")
                webSocket.close(1000, null)
                if (code == 1000 || reason == "ok") ended.complete(Unit)
                else ended.completeExceptionally(RexError("upload_failed", reason.ifEmpty { "Upload failed ($code)" }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = RexError("upload_failed", t.message ?: "Upload failed")
                welcomed.completeExceptionally(error)
                ended.completeExceptionally(error)
            }
        })
        sockets += socket
        withTimeoutOrNull(timeoutMillis) { welcomed.await() }
            ?: throw RexError("timeout", "The upload connection did not respond")
        file.open().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                withTimeoutOrNull(timeoutMillis) {
                    while (socket.queueSize() > MAX_QUEUED) delay(10)
                    true
                } ?: throw RexError("timeout", "The upload stopped making progress")
                if (!socket.send(buffer.toByteString(0, n))) throw RexError("upload_failed", "The upload connection closed")
                sentBytes(n)
            }
        }
        if (!socket.send(ByteString.EMPTY)) throw RexError("upload_failed", "The upload connection closed") // An empty message ends the bytes; the server closes once the batch is done
        return ended
    }

    private fun ByteString.getOrNull(index: Int): Int? = if (index < size) this[index].toInt() and 0xFF else null

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_QUEUED = 1L shl 20
    }
}
