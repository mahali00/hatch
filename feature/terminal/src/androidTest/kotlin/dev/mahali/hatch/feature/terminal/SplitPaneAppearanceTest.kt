package dev.mahali.hatch.feature.terminal

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.Title
import dev.mahali.hatch.design.Chrome
import dev.mahali.hatch.design.HatchTheme
import dev.mahali.hatch.ghostty.FrameExchange
import dev.mahali.hatch.renderer.CellMetrics
import dev.mahali.hatch.renderer.TerminalFont
import dev.mahali.hatch.renderer.TerminalView
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class SplitPanePreviewActivity : ComponentActivity() {
    private val first = BlockId("first")
    private val second = BlockId("second")
    internal val stage = TerminalViewport({ 0L }, { _, _ -> {} })
    var stacked by mutableStateOf(false)
    var light by mutableStateOf(false)
    private var origin = Offset.Zero
    private var canvasColor = 0
    private var terminalColor = 0
    private var stripHeight = 0f
    private val cell = CellMetrics.of(TerminalFont(Typeface.MONOSPACE, sizePx = 16f))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val palette = if (light) Chrome.NeutralLight else Chrome.NeutralDark.copy(terminal = Color(0xFF161616))
            val canvas = palette.workspace
            val density = LocalDensity.current
            val layout = Layout.Split(if (stacked) Axis.Column else Axis.Row, 0.5f, Layout.Leaf(first), Layout.Leaf(second))
            SideEffect {
                canvasColor = canvas.toArgb()
                terminalColor = palette.terminal.toArgb()
                stripHeight = with(density) { StripHeight.toPx() }
                stage.metrics(ViewportMetrics(density.density, cell.width, cell.height, stripHeight.roundToInt()))
                stage.layout(layout, Tiles, first)
            }
            HatchTheme(palette) {
                Box(Modifier.fillMaxSize().background(canvas)) {
                    PaneTiles(
                        stage, false, remember { CanvasGestures() },
                        strip = { TileStrip(ProgramKey.Terminal, Title(if (it == first) "Development" else "Tests")) },
                        onSelect = {}, onPickUp = {}, onDropTarget = {}, onDropped = {}, onAction = { _, _ -> },
                        onResize = { _, _ -> }, onColumnResize = { _, _ -> }, onDrop = { _, _, _ -> },
                        modifier = Modifier.fillMaxSize().onSizeChanged { stage.area(it) }
                            .onGloballyPositioned { origin = it.localToScreen(Offset.Zero) },
                    ) { pane ->
                        PaneTerminal(stage, pane.block, Modifier.fillMaxSize()) { NativePanel(palette.terminal.toArgb()) }
                    }
                }
            }
        }
    }

    data class Probe(val x: Int, val y: Int, val color: Int)

    fun probes(): List<Probe> {
        if (stage.placed.value.size != 2) return emptyList()
        fun at(x: Float, y: Float, color: Int) = Probe((origin.x + x).roundToInt(), (origin.y + y).roundToInt(), color)
        return stage.placed.value.flatMap { pane ->
            val r = pane.inner
            listOf(
                at(r.left + 6f, r.bottom - 6f, terminalColor),
                at(r.right - 6f, r.bottom - 6f, terminalColor),
                at((r.left + r.right) / 2f, r.top + stripHeight + 8f, terminalColor),
            )
        } + stage.dividers.value.map { divider ->
            if (divider.vertical) at(divider.at, divider.bounds.top + stripHeight + 8f, canvasColor)
            else at(divider.bounds.left + 8f, divider.at, canvasColor)
        }
    }
}

@Composable
private fun NativePanel(background: Int) {
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val exchange = remember { FrameExchange() }
    val slot = LocalSlotClip.current
    LaunchedEffect(view, slot) {
        val current = view ?: return@LaunchedEffect
        if (slot != null) snapshotFlow { slot.size }.collect { size ->
            current.clipBounds = size?.let { android.graphics.Rect(0, 0, it.width, it.height) }
        }
    }
    DisposableEffect(view) { onDispose { view?.setSource(null) } }
    AndroidView(
        factory = { context ->
            TerminalView(context).apply {
                setFont(TerminalFont(Typeface.MONOSPACE, sizePx = 16f))
                setBackground(background)
                setBlink(0)
                setSource(exchange)
                view = this
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { it.setBackground(background); it.setSource(exchange) },
    )
}

@RunWith(AndroidJUnit4::class)
class SplitPaneAppearanceTest {
    @Test fun panelsKeepNativeSurfacesInsideGuttersDuringResizeAndInBothThemes(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.context, SplitPanePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<SplitPanePreviewActivity>(intent).use { scenario ->
            suspend fun capture(name: String) {
                var mismatch = "No pixels captured"
                try { withTimeout(10_000) {
                    while (true) {
                        instrumentation.waitForIdleSync()
                        var probes = emptyList<SplitPanePreviewActivity.Probe>()
                        scenario.onActivity { probes = it.probes() }
                        val screenshot = instrumentation.uiAutomation.takeScreenshot()
                        mismatch = probes.mapNotNull { probe ->
                            if (screenshot == null || probe.x !in 0 until screenshot.width || probe.y !in 0 until screenshot.height) "Outside screenshot: $probe"
                            else screenshot.getPixel(probe.x, probe.y).let { actual ->
                                if (similar(actual, probe.color)) null else "(${probe.x},${probe.y}) expected=${probe.color.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}"
                            }
                        }.joinToString()
                        val matches = screenshot != null && probes.isNotEmpty() && probes.all { probe ->
                            if (probe.x !in 0 until screenshot.width || probe.y !in 0 until screenshot.height) false
                            else similar(screenshot.getPixel(probe.x, probe.y), probe.color)
                        }
                        if (matches) {
                            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
                            output.outputStream().use { screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            screenshot!!.recycle()
                            return@withTimeout
                        }
                        screenshot?.recycle()
                        delay(100)
                    }
                } } catch (e: TimeoutCancellationException) {
                    throw AssertionError("$name: $mismatch", e)
                }
            }
            capture("split-panels-dark")
            scenario.onActivity { it.stage.beginDividerDrag(); it.stage.preview("", 0.65f) }
            capture("split-panels-dragging")
            scenario.onActivity { it.stage.preview(null); it.stage.endDividerDrag(); it.stacked = true; it.light = true }
            capture("split-panels-light-stacked")
            scenario.onActivity { activity -> assertTrue(activity.stage.placed.value.all { it.grid != null }) }
        }
    }

    private fun similar(actual: Int, expected: Int): Boolean = (0..2).all { channel ->
        abs((actual shr (channel * 8) and 255) - (expected shr (channel * 8) and 255)) <= 3
    }
}
