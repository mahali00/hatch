package dev.mahali.hatch.renderer

import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mahali.hatch.ghostty.FrameExchange
import dev.mahali.hatch.ghostty.GhosttyTerminal
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderOwnershipTest {
    @Test fun oneExchangeCanMoveBetweenRenderTargetsAfterSynchronousDetach() {
        val exchange = FrameExchange()
        val font = TerminalFont(Typeface.MONOSPACE, sizePx = 16f)
        val cell = CellMetrics.of(font)
        GhosttyTerminal(40, 12).use { terminal ->
            repeat(4) { visit ->
                val text = "\r\nattachment $visit".toByteArray()
                val input = ByteBuffer.allocateDirect(text.size).apply { put(text); flip() }
                terminal.write(input) {}
                terminal.snapshot(exchange.backFrame())
                exchange.publish()

                ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { images ->
                    images.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                    val state = DrawState(source = exchange, blinkMillis = 0)
                    val target = TerminalGl.attach(images.surface, 320, 240, font, cell, state)
                    try {
                        val rendered = CountDownLatch(1)
                        target.renderThen(Runnable { rendered.countDown() })
                        assertTrue("GLES consumer did not render attachment $visit", rendered.await(10, TimeUnit.SECONDS))
                        assertNotNull("A redraw callback alone is not evidence of a rendered frame", state.renderedBackground)
                    } finally {
                        // Must acknowledge GL removal before the next target can consume this SPSC exchange.
                        target.finish()
                        target.finish()
                    }
                }
            }
        }
    }
}
