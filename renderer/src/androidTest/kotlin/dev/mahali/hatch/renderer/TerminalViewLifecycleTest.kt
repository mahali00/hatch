package dev.mahali.hatch.renderer

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Size
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahali.hatch.ghostty.FrameExchange
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test APK only. */
class TerminalViewLifecycleActivity : Activity() {
    lateinit var terminal: TerminalView
        private set
    val initialRedraw = CountDownLatch(1)
    var surfaceChanges = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        terminal = TerminalView(this).apply {
            setFont(TerminalFont(Typeface.MONOSPACE, sizePx = 16f))
            setBackground(0x125634)
            setBlink(0)
        }
        terminal.holder.addCallback(object : SurfaceHolder.Callback2 {
            override fun surfaceCreated(holder: SurfaceHolder) { }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceChanges++ }
            override fun surfaceDestroyed(holder: SurfaceHolder) { }
            override fun surfaceRedrawNeeded(holder: SurfaceHolder) { initialRedraw.countDown() }
            override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
                drawingFinished.run()
                initialRedraw.countDown()
            }
        })
        setContentView(terminal, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@RunWith(AndroidJUnit4::class)
class TerminalViewLifecycleTest {
    @Test fun sourceAttachedAfterSourcelessInitialRedrawRevealsWithoutResize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.context, TerminalViewLifecycleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<TerminalViewLifecycleActivity>(intent).use { scenario ->
            lateinit var initialRedraw: CountDownLatch
            scenario.onActivity { initialRedraw = it.initialRedraw }
            assertTrue("Android did not complete the initial sourceless redraw", initialRedraw.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            var size = Size(0, 0)
            var changes = 0
            var grid: Grid? = null
            val revealed = CountDownLatch(1)
            lateinit var drawListener: ViewTreeObserver.OnDrawListener
            scenario.onActivity { activity ->
                val view = activity.terminal
                assertTrue("A real window surface is required", view.holder.surface.isValid)
                assertTrue(view.width > 0 && view.height > 0)
                assertEquals("The initial redraw had no renderer to reveal", Size(0, 0), view.presentedSize)
                size = Size(view.width, view.height)
                changes = activity.surfaceChanges
                drawListener = ViewTreeObserver.OnDrawListener {
                    if (view.presentedSize == size) revealed.countDown()
                }
                view.viewTreeObserver.addOnDrawListener(drawListener)
                // Intentionally after surfaceCreated, surfaceChanged and the initial redraw.
                view.onGrid = { grid = it }
                view.setSource(FrameExchange())
                assertEquals("setSource must wait for a render acknowledgment before revealing", Size(0, 0), view.presentedSize)
            }
            try {
                assertTrue("Late source attachment stayed clipped without a resize", revealed.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertEquals(size, activity.terminal.presentedSize)
                    assertEquals("The regression must not be rescued by surfaceChanged", changes, activity.surfaceChanges)
                    assertEquals(size, Size(activity.terminal.width, activity.terminal.height))
                    val cell = requireNotNull(activity.terminal.cell)
                    assertEquals("The grid must use the whole view width", size.width / cell.width, requireNotNull(grid).cols)
                    assertEquals("Selection starts at the same origin as drawing", 0f, activity.terminal.cellLeft(0), 0f)
                }
            } finally {
                scenario.onActivity { activity ->
                    activity.terminal.viewTreeObserver.removeOnDrawListener(drawListener)
                    activity.terminal.setSource(null)
                    assertEquals(Size(0, 0), activity.terminal.presentedSize)
                }
            }
        }
    }
}
