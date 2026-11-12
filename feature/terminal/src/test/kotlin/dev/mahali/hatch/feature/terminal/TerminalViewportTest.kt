package dev.mahali.hatch.feature.terminal

import org.junit.Assert.assertNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.GridSize
import dev.mahali.hatch.core.Layout
import org.junit.Assert.*
import org.junit.Test

class TerminalViewportTest {
    private val a = BlockId("a")
    private val b = BlockId("b")
    private val c = BlockId("c")
    private val d = BlockId("d")
    private val single = Layout.Leaf(a)
    private val pair = Layout.Split(Axis.Row, 0.5f, Layout.Leaf(a), Layout.Leaf(b))
    private val grid = Layout.Split(Axis.Row, 0.5f,
        Layout.Split(Axis.Column, 0.5f, Layout.Leaf(a), Layout.Leaf(b)),
        Layout.Split(Axis.Column, 0.5f, Layout.Leaf(c), Layout.Leaf(d)))
    private val three = Layout.Split(Axis.Row, 0.33f, Layout.Leaf(a), Layout.Split(Axis.Row, 0.5f, Layout.Leaf(b), Layout.Leaf(c)))

    // 2x density: gutter 12 px, strip 64 px, 10 x 20 cells, no side padding.
    private val metrics = ViewportMetrics(density = 2f, cellWidth = 10, cellHeight = 20, stripPx = 64)
    private val area = IntSize(1000, 800)

    private var now = 0L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private fun advanceTo(t: Long) {
        now = t
        timers.filter { it.first <= t }.forEach { timers.remove(it); it.second() }
    }

    private fun viewport(layout: Layout = grid, mode: LayoutMode = Tiles, focused: BlockId? = a) = TerminalViewport(
        clock = { now },
        after = { ms, action -> val timer = (now + ms) to action; timers += timer; { timers.remove(timer) } },
    ).apply { metrics(metrics); layout(layout, mode, focused); area(area) }

    private fun TerminalViewport.pane(block: BlockId) = placed.value.single { it.block == block }
    private fun grid(cols: Int, rows: Int) = GridSize(cols, rows, 10, 20)
    private fun rect(x: Int, y: Int, w: Int, h: Int) = IntRect(IntOffset(x, y), IntSize(w, h))


    @Test fun `tiles scale a 2x2 layout to the area with shared gutters and strips`() {
        val v = viewport()
        assertEquals(listOf(a, b, c, d), v.placed.value.map { it.block })
        assertEquals(rect(0, 0, 500, 400), v.pane(a).rect)
        assertEquals(rect(500, 400, 500, 400), v.pane(d).rect)
        assertEquals(IntSize(482, 318), contentSize(v.pane(a).rect, area, true, true, metrics))
        assertEquals(IntSize(482, 318), contentSize(v.pane(d).rect, area, true, true, metrics))
        v.placed.value.forEach { assertEquals(grid(48, 15), it.grid) }
        assertTrue(v.placed.value.all { it.strip && it.visible })
        assertEquals(listOf(false, true, true, true), v.placed.value.map { it.faded })
    }

    @Test fun `a single tiled pane is framed in the gutter, with no strip`() {
        val v = viewport(single)
        val p = v.pane(a)
        assertEquals(rect(0, 0, 1000, 800), p.rect)
        assertEquals(rect(12, 12, 976, 776), p.inner)
        assertTrue(p.framed)
        assertFalse(p.strip)
        assertFalse(p.faded)
        assertEquals(grid(97, 38), p.grid)
        // A page is full bleed.
        assertEquals(grid(100, 40), viewport(single, Pages()).pane(a).grid)
    }

    @Test fun `pages give each pane the full area without title strips or fading`() {
        val v = viewport(pair, Pages(), focused = b)
        assertEquals(IntSize(2000, 800), v.canvas)
        assertEquals(rect(0, 0, 1000, 800), v.pane(a).rect)
        assertEquals(rect(1000, 0, 1000, 800), v.pane(b).rect)
        assertEquals(grid(100, 40), v.pane(a).grid)
        assertTrue(v.placed.value.none { it.strip })
        assertTrue(v.placed.value.none { it.faded })
        assertEquals(1000f, v.scroll.x) // the focused page is revealed
        assertFalse(v.pane(a).visible)
        assertTrue(v.pane(b).visible)
    }

    @Test fun `columns follow scrollStrip's canvas and reveal the focused column`() {
        val v = viewport(three, Columns)
        // Three columns of 260 dp (the minimum) at 2x.
        assertEquals(IntSize(1560, 800), v.canvas)
        assertEquals(listOf(rect(0, 0, 520, 800), rect(520, 0, 520, 800), rect(1040, 0, 520, 800)), v.placed.value.map { it.rect })
        assertEquals(listOf(true, true, false), v.placed.value.map { it.visible })
        v.layout(three, Columns, c)
        assertEquals(560f, v.scroll.x)
        assertEquals(listOf(false, true, true), v.placed.value.map { it.visible })
        v.scrollBy(10_000f, 0f)
        assertEquals(560f, v.scroll.x) // clamped to the canvas
        assertEquals(listOf(true, true, false), v.placed.value.map { it.faded })
    }

    @Test fun `with a canvas on screen a reveal waits to be scrolled, and a finger ends it`() {
        val v = viewport(three, Columns)
        v.animatesReveals = true
        v.layout(three, Columns, c)
        assertEquals(0f, v.scroll.x) // not yet: the canvas scrolls there
        assertEquals(Offset(560f, 0f), v.revealTarget)
        v.scrollTo(Offset(280f, 0f)); assertEquals(280f, v.scroll.x)
        v.revealArrived(Offset(560f, 0f)); assertNull(v.revealTarget)
        v.layout(three, Columns, a)
        assertEquals(Offset(0f, 0f), v.revealTarget)
        v.scrollBy(-10f, 0f) // a finger takes over
        assertNull(v.revealTarget)
        v.layout(three, Columns, c)
        v.animatesReveals = false // the canvas left: the reveal lands at once
        assertEquals(560f, v.scroll.x)
        assertNull(v.revealTarget)
    }

    // Rule 1: one commit per motion, at its start.

    @Test fun `a slide commits its first size and holds until it stops`() {
        val v = viewport(single, Pages())
        v.motion(AreaMotion.Slide { 0f })
        v.area(IntSize(700, 800))
        assertEquals(grid(70, 40), v.grids[a])
        v.area(IntSize(650, 800))
        assertEquals(grid(70, 40), v.grids[a])
        assertEquals(650, v.pane(a).rect.width) // placement follows at once
        v.motion(AreaMotion.Still)
        assertEquals(grid(65, 40), v.grids[a])
    }

    @Test fun `a burst commits at its start and once more when it is still`() {
        val v = viewport(single, Pages())
        advanceTo(1000)
        v.motion(AreaMotion.Burst)
        v.area(IntSize(900, 800))
        assertEquals(grid(90, 40), v.grids[a]) // first step after a quiet spell: at once
        advanceTo(1050); v.area(IntSize(800, 800))
        advanceTo(1100); v.area(IntSize(700, 800))
        advanceTo(1249)
        assertEquals(grid(90, 40), v.grids[a])
        advanceTo(1250)
        assertEquals(grid(70, 40), v.grids[a])
    }

    @Test fun `a rotation or fold resizes once at its start and once when it settles, with no motion sent`() {
        val v = viewport(single, Pages())
        val seen = mutableListOf(v.grids[a])
        fun step(t: Long, size: IntSize) { advanceTo(t); v.area(size); seen += v.grids[a] }
        // The window swaps its sides, then the insets move in a few steps.
        step(1000, IntSize(800, 1000))
        step(1016, IntSize(800, 960))
        step(1032, IntSize(780, 960))
        advanceTo(1182); seen += v.grids[a]
        assertEquals(listOf(grid(100, 40), grid(80, 50), grid(80, 50), grid(80, 50), grid(78, 48)), seen)
    }

    @Test fun `still ends a burst with a commit at once`() {
        val v = viewport(single, Pages())
        advanceTo(1000); v.motion(AreaMotion.Burst); v.area(IntSize(900, 800))
        advanceTo(1010); v.area(IntSize(800, 800))
        v.motion(AreaMotion.Still)
        assertEquals(grid(80, 40), v.grids[a])
        assertTrue(timers.isEmpty())
    }

    // Rule 2: no lift while the cursor is unknown; only the focused pane, only as far as its line needs.

    private val keyboardUp = KeyboardInset(nowPx = 300f, targetPx = 300f)

    @Test fun `nothing lifts while the focused cursor is unknown`() {
        val v = viewport(single, Pages())
        v.keyboard(keyboardUp)
        assertEquals(0f, v.pane(a).lift)
        v.cursor(a, 700f)
        assertEquals(200f, v.pane(a).lift)
        v.cursor(a, null)
        assertEquals(0f, v.pane(a).lift)
    }

    @Test fun `a line above the keyboard does not lift, and the lift never passes the overlap`() {
        val v = viewport(single, Pages())
        v.keyboard(keyboardUp)
        v.cursor(a, 100f)
        assertEquals(0f, v.pane(a).lift)
        v.cursor(a, 790f)
        assertEquals(290f, v.pane(a).lift)
        v.keyboard(KeyboardInset())
        assertEquals(0f, v.pane(a).lift)
    }

    @Test fun `only the focused tile lifts, measured below its strip`() {
        val v = viewport(grid, Tiles, focused = b)
        v.keyboard(keyboardUp)
        v.cursor(a, 700f) // not focused: ignored
        v.cursor(b, 100f)
        assertEquals(70f, v.pane(b).lift)
        assertTrue(v.placed.value.filter { it.block != b }.all { it.lift == 0f })
    }

    // Rule 3: a mode switch and its grids commit together.

    @Test fun `a mode switch and its grids apply in one snapshot`() {
        val v = viewport(pair, Pages())
        assertEquals(grid(100, 40), v.grids[a])
        var applies = 0
        val handle = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try { v.layout(pair, Tiles, a) } finally { handle.dispose() }
        assertEquals(1, applies)
        // 482 x (800 - 24 - 64): outer gutters and title strip are outside terminal cells.
        assertEquals(listOf(grid(48, 35), grid(48, 35)), v.placed.value.map { it.grid })
        assertEquals(rect(500, 0, 500, 800), v.pane(b).rect)
    }

    // Rule 4: a divider drag freezes grids until release.

    @Test fun `a divider drag moves panes but holds grids until release`() {
        val v = viewport(pair)
        v.beginDividerDrag()
        val preview = pair.resized("", 0.7f)
        v.layout(preview, Tiles, a)
        assertEquals(700, v.pane(a).rect.width)
        assertEquals(grid(48, 35), v.grids[a])
        v.area(IntSize(900, 800))
        assertEquals(grid(48, 35), v.grids[a])
        v.endDividerDrag()
        assertEquals(grid(61, 35), v.grids[a])
    }

    @Test fun `a previewed ratio moves panes and their line, and commits its own grid on release`() {
        val v = viewport(pair)
        assertEquals(500f, v.dividers.value.single().at)
        v.beginDividerDrag()
        v.preview("", 0.7f)
        assertEquals(700, v.pane(a).rect.width)
        assertEquals(700f, v.dividers.value.single().at)
        assertEquals(1000, v.dividers.value.single().extent)
        assertEquals(grid(48, 35), v.grids[a])
        v.endDividerDrag()
        assertEquals(grid(68, 35), v.grids[a])
        v.preview(null)
        assertEquals(500, v.pane(a).rect.width)
    }

    @Test fun `panels share a gutter and columns have grips only between them`() {
        val v = viewport()
        assertEquals(rect(12, 12, 482, 382), v.pane(a).inner)
        assertEquals(rect(506, 406, 482, 382), v.pane(d).inner)
        assertEquals(rect(0, 0, 1000, 800), viewport(single, Pages()).pane(a).inner)
        val c = viewport(three, Columns)
        assertTrue(c.dividers.value.none { it.divider.path == "end" })
    }

    @Test fun `composed ignores per-frame geometry`() {
        val v = viewport(single, Pages())
        val before = v.composed.value
        v.keyboard(keyboardUp)
        v.cursor(a, 790f)
        assertTrue(v.pane(a).lift > 0f)
        assertEquals(before, v.composed.value)
        v.layout(single, Pages(), null)
        assertNotEquals(before, v.composed.value)
    }

    @Test fun `a pane that appears mid-drag still gets a grid`() {
        val v = viewport(single)
        v.beginDividerDrag()
        v.layout(pair, Tiles, a)
        assertEquals(grid(97, 38), v.grids[a]) // the lone tile's grid, held through the drag
        assertNotNull(v.grids[b])
    }

    // Rule 5: grids come from the slot's final size in the current mode.

    @Test fun `a burst pending across a mode switch commits the new mode's size`() {
        val v = viewport(pair, Pages())
        advanceTo(1000); v.area(IntSize(900, 800))
        advanceTo(1020); v.area(IntSize(800, 800))
        v.layout(pair, Tiles, a)
        assertEquals(grid(38, 35), v.grids[a]) // the tile at 800, not the page at 900
        advanceTo(1200)
        assertEquals(grid(38, 35), v.grids[a])
    }

    @Test fun `a mode switch commits even mid-drag`() {
        val v = viewport(pair)
        v.beginDividerDrag()
        v.layout(pair, Pages(), a)
        assertEquals(grid(100, 40), v.grids[a])
    }

    @Test fun `grids drop panes that left the layout`() {
        val v = viewport(grid)
        v.layout(pair, Tiles, a)
        assertEquals(setOf(a, b), v.grids.keys)
    }

    @Test fun `outer and shared gutters stay equal even with odd pixel rounding`() {
        val v = viewport()
        v.metrics(metrics.copy(density = 1.5f)) // 6 dp rounds to 9 px, split 4 + 5 between neighbours.
        val topLeft = v.pane(a).inner
        val bottomLeft = v.pane(b).inner
        val topRight = v.pane(c).inner
        val bottomRight = v.pane(d).inner
        assertEquals(9, topLeft.left)
        assertEquals(9, topLeft.top)
        assertEquals(9, topRight.left - topLeft.right)
        assertEquals(9, bottomLeft.top - topLeft.bottom)
        assertEquals(9, area.width - bottomRight.right)
        assertEquals(9, area.height - bottomRight.bottom)
        v.placed.value.forEach { pane ->
            assertEquals(pane.inner.width / metrics.cellWidth, v.grids.getValue(pane.block).cols)
        }
    }
}
