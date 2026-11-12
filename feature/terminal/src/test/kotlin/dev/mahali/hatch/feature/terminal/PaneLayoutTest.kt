package dev.mahali.hatch.feature.terminal

import androidx.compose.ui.unit.IntSize
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Rect
import dev.mahali.hatch.core.blocks
import dev.mahali.hatch.core.rects
import org.junit.Assert.*
import org.junit.Test

class PaneLayoutTest {
    private val a = Layout.Leaf(BlockId("a"))
    private val b = Layout.Leaf(BlockId("b"))
    private val c = Layout.Leaf(BlockId("c"))
    private val d = Layout.Leaf(BlockId("d"))
    private val grid = Layout.Split(Axis.Row, 0.5f,
        Layout.Split(Axis.Column, 0.5f, a, b), Layout.Split(Axis.Column, 0.5f, c, d))

    @Test fun `nested borders identify the split and its own coordinate space`() {
        val borders = grid.dividers()
        assertEquals(listOf("", "0", "1"), borders.map { it.path })
        assertEquals(a.block, borders[0].before)
        assertEquals(c.block, borders[0].after)
        assertEquals(Rect(0.5f, 0f, 0.5f, 1f), borders[2].bounds)
        assertEquals(Axis.Column, borders[2].axis)
        assertEquals(c.block, borders[2].before)
        assertEquals(d.block, borders[2].after)
        assertNull(borders[0].adjacent)
        assertEquals(c.block to d.block, borders[2].adjacent)
    }

    @Test fun `resize preview changes only the chosen split and preserves pane identities`() {
        val resized = grid.resized("1", 0.7f) as Layout.Split
        assertEquals(grid.before, resized.before)
        assertEquals(0.5f, resized.ratio)
        assertEquals(0.7f, (resized.after as Layout.Split).ratio)
        assertEquals(grid.blocks(), resized.blocks())
        assertEquals(0.5f, (grid.after as Layout.Split).ratio)
    }

    @Test fun `scroll columns keep independent widths and push following columns right`() {
        val columns = Layout.Split(Axis.Row, 0.5f,
            Layout.Split(Axis.Row, 0.5f, a, b), Layout.Split(Axis.Row, 0.5f, c, d))
        val initial = columns.scrollStrip(900f, 700f, emptyMap())
        assertEquals(CanvasSize(1800f, 700f), initial.canvas)
        val wider = columns.scrollStrip(900f, 700f, mapOf("b" to 1f))
        assertEquals(CanvasSize(2250f, 700f), wider.canvas)
        val rects = wider.layout.rectsInPixels(wider.canvas)
        assertEquals(450f, rects.getValue(a.block).width, 0.01f)
        assertEquals(900f, rects.getValue(b.block).width, 0.01f)
        assertEquals(1350f, rects.getValue(c.block).x, 0.01f)
        assertEquals(450f, rects.getValue(d.block).width, 0.01f)
    }

    @Test fun `the last column fills the screen when the others leave room`() {
        val two = Layout.Split(Axis.Row, 0.5f, a, b)
        val strip = two.scrollStrip(900f, 700f, mapOf("a" to 1f / 3f, "b" to 1f / 3f))
        assertEquals(CanvasSize(900f, 700f), strip.canvas)
        assertEquals(600f, strip.layout.rectsInPixels(strip.canvas).getValue(b.block).width, 0.01f)
    }

    @Test fun `stacks stay inside columns and never add vertical workspace scrolling`() {
        val strip = grid.scrollStrip(390f, 640f, emptyMap())
        assertEquals(CanvasSize(520f, 640f), strip.canvas)
        assertEquals(2, grid.scrollColumns().size)
        assertEquals(listOf(a.block, b.block), grid.scrollColumns()[0].blocks())
        assertEquals(0.5f, strip.layout.rectsInPixels(strip.canvas).getValue(a.block).height / 640f, 0.001f)
        val stack = Layout.Split(Axis.Column, 0.5f, a, Layout.Split(Axis.Column, 0.5f, b, c))
        assertEquals(CanvasSize(390f, 270f), stack.scrollStrip(390f, 270f, emptyMap()).canvas)
    }

    @Test fun `uneven desktop trees project every pane once into stacked columns`() {
        val uneven = Layout.Split(Axis.Column, 0.4f, a, Layout.Split(Axis.Row, 0.5f, b, c))
        val cols = uneven.scrollColumns()
        assertEquals(listOf(a.block, b.block, c.block), cols.flatMap { it.blocks() })
        assertEquals(2, cols.size)
        assertEquals(listOf(a.block, b.block), cols[0].blocks())
        assertEquals(listOf(c.block), cols[1].blocks())
        assertEquals(0.4f, (cols[0] as Layout.Split).ratio, 0.001f)
    }

    @Test fun `a column is at least 260 dp and up to twice the screen, and widens past it`() {
        assertEquals(260f, columnWidth(390f, 0.1f), 0.001f)
        assertEquals(585f, columnWidth(390f, 1.5f), 0.001f)
        assertEquals(780f, columnWidth(390f, 5f), 0.001f)
        assertEquals(0.5f, nextColumnWidth(1f / 3f, true), 0.001f)
        assertEquals(1.5f, nextColumnWidth(1f, true), 0.001f)
        assertEquals(2f, nextColumnWidth(2f, true), 0.001f)
        assertEquals(2f / 3f, nextColumnWidth(1f, false), 0.001f)
    }

    @Test fun `a long tape shows only the panes in its viewport`() {
        val many = (0 until 128).map { Layout.Leaf(BlockId("pane-$it")) as Layout }
            .reduceRight { column, rest -> Layout.Split(Axis.Row, 0.5f, column, rest) }
        val v = TerminalViewport(clock = { 0L }, after = { _, _ -> {} }).apply {
            metrics(ViewportMetrics(1f, 10, 20, 32)); layout(many, Columns, null); area(IntSize(900, 700))
        }
        assertEquals(57600, v.canvas.width)
        v.scrollBy(27000f, 0f)
        val visible = v.placed.value.filter { it.visible }
        assertTrue(visible.size in 2..3)
        assertEquals(128, v.placed.value.size)
        assertTrue(visible.all { it.rect.width <= 451 })
    }

    private fun Layout.rectsInPixels(size: CanvasSize) = rects().mapValues { (_, r) ->
        Rect(r.x * size.width, r.y * size.height, r.width * size.width, r.height * size.height)
    }
}
