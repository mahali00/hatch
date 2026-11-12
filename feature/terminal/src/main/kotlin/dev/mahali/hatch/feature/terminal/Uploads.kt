package dev.mahali.hatch.feature.terminal

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.protocol.RexError
import dev.mahali.hatch.protocol.UploadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.NonCancellable

internal sealed interface UploadPill {
    data class Sending(val label: String, val sent: Long, val total: Long) : UploadPill
    data class Failed(val reason: String) : UploadPill
}

@Stable
internal class Uploads(private val rex: Rex, private val context: Context, private val scope: CoroutineScope) {
    var pill: UploadPill? by mutableStateOf(null)
        private set

    fun dismiss() { pill = null }

    fun paste(ref: PaneRef) {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isNotEmpty()) return upload(ref, uris)
        val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
        if (text.isNotEmpty()) rex.paste(ref.host, ref.session, ref.block, text)
    }

    private class Prepared(val source: UploadSource, val cleanup: () -> Unit = {})

    // [done] runs after the batch, when a keyboard's read grant can be released.
    fun upload(ref: PaneRef, uris: List<Uri>, done: () -> Unit = {}) {
        scope.launch {
            val prepared = mutableListOf<Prepared>()
            try {
                withContext(Dispatchers.IO) { uris.forEach { prepared += source(it) } }
                val files = prepared.map { it.source }
                val label = files.singleOrNull()?.let { "Uploading ${it.name}" } ?: "Uploading ${files.size} files"
                pill = UploadPill.Sending(label, 0, files.sumOf { it.size })
                rex.upload(ref.host, ref.session, ref.block, files) { sent, total ->
                    scope.launch { pill = UploadPill.Sending(label, sent, total) }
                }
                pill = null
            } catch (e: CancellationException) {
                pill = null
                throw e
            } catch (e: RexError) {
                pill = UploadPill.Failed(uploadFailure(e.code, e.message))
            } catch (e: Exception) {
                pill = UploadPill.Failed(e.message ?: "the file could not be read")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { prepared.forEach { it.cleanup() } }
                done()
            }
        }
    }

    private fun source(uri: Uri): Prepared {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size >= 0) return Prepared(UploadSource(name, size) { resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open $name") })
        val temp = File.createTempFile("hatch-upload-", ".tmp", context.cacheDir)
        try {
            val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open $name")
            input.use { from -> temp.outputStream().use { to -> from.copyTo(to) } }
            return Prepared(UploadSource(name, temp.length()) { FileInputStream(temp) }, { temp.delete() })
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }
}

internal fun uploadFailure(code: String, message: String?): String = when (code) {
    "too_large" -> "file too large"
    "conflict" -> "too many uploads at once"
    "method_not_found" -> "this server can't receive files"
    else -> message?.takeIf { it.isNotBlank() } ?: code
}
