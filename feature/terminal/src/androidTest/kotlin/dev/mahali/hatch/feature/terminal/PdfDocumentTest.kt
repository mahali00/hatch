package dev.mahali.hatch.feature.terminal

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfDocumentTest {
    @Test fun cancelledPageHandoffRecyclesItsBoundedBitmapAndClosingDeletesTheCopy(): Unit = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pdf-test-${UUID.randomUUID()}")
        val copy = PreviewCopy.create(root, "tall.pdf")
        val source = AndroidPdfDocument()
        try {
            val page = source.startPage(AndroidPdfDocument.PageInfo.Builder(1, 14400, 1).create())
            source.finishPage(page)
            copy.file.outputStream().use { source.writeTo(it) }
        } finally {
            source.close()
        }
        val renderer = PdfRenderer(ParcelFileDescriptor.open(copy.file, ParcelFileDescriptor.MODE_READ_ONLY))
        val document = PdfDocument(copy, renderer)
        val transferred = CompletableDeferred<Unit>()
        val returnToCaller = CountDownLatch(1)
        var rendered: Bitmap? = null
        val caller = launch(Dispatchers.Default) {
            try {
                document.render(0, 2500) { bitmap ->
                    rendered = bitmap
                    transferred.complete(Unit)
                    check(returnToCaller.await(10, TimeUnit.SECONDS))
                }
            } finally {
                rendered?.recycle()
            }
        }
        try {
            withTimeout(10_000) { transferred.await() }
            caller.cancel()
            returnToCaller.countDown()
            withTimeout(10_000) { caller.join() }
            val bitmap = rendered
            assertNotNull(bitmap)
            assertTrue(bitmap!!.width in 1..4096 && bitmap.height in 1..4096)
            assertTrue(bitmap.width.toLong() * bitmap.height <= previewLimits.pdfPagePixels)
            assertTrue(bitmap.isRecycled)
        } finally {
            returnToCaller.countDown()
            caller.cancel()
            caller.join()
            document.close()
            assertFalse(copy.file.parentFile!!.exists())
            root.delete()
        }
    }
}
