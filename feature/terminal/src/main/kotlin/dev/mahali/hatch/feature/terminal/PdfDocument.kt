package dev.mahali.hatch.feature.terminal

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Ownership: the renderer and its PreviewCopy close together; close waits for page work in progress.
internal class PdfDocument(private val local: PreviewCopy, private val renderer: PdfRenderer) {
    private val thread = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val lock = Mutex()
    private val aspects = FloatArray(renderer.pageCount)
    val pages = renderer.pageCount
    private var closed = false

    fun aspect(index: Int) = aspects[index].takeIf { it > 0f } ?: (1f / 1.414f)

    // Transfers the bitmap on this worker, so cancelling the caller cannot lose ownership.
    suspend fun render(index: Int, width: Int, onRendered: (Bitmap) -> Unit) = withContext(thread) {
        lock.withLock {
            if (closed) return@withLock
            renderer.openPage(index).use { page ->
                aspects[index] = page.width.toFloat() / page.height
                val size = pdfPageSize(page.width, page.height, width, previewLimits.pdfPagePixels)
                val bitmap = createBitmap(size.width, size.height)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    onRendered(bitmap)
                } catch (e: Throwable) {
                    bitmap.recycle()
                    throw e
                }
            }
        }
    }

    suspend fun close() {
        try {
            withContext(thread) {
                lock.withLock {
                    if (!closed) {
                        closed = true
                        renderer.close()
                    }
                }
            }
        } finally {
            local.close()
            thread.close()
        }
    }
}
