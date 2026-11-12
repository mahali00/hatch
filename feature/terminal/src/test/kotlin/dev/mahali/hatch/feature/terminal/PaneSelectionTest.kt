package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.core.BlockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneSelectionTest {
    private val a = BlockId("a")
    private val b = BlockId("b")
    private val c = BlockId("c")
    private val initial = PaneSelection().reconcile(listOf(a, b, c), b)

    @Test fun `removing B from A B C uses server focus rather than B's old pager index`() {
        val selected = initial.choose(b)
        val oldPager = selected.boundary()
        val removed = selected.reconcile(listOf(a, c), a)
        assertEquals(a, removed.current)
        assertEquals(removed, removed.swipe(oldPager, 1))
        assertNull(removed.local)
    }

    @Test fun `reorder preserves the selected identity and rejects an old index`() {
        val selected = initial.choose(b)
        val boundary = selected.boundary()
        val reordered = selected.reconcile(listOf(c, a, b), a)
        assertEquals(b, reordered.current)
        assertEquals(2, reordered.panes.indexOf(reordered.current))
        assertEquals(reordered, reordered.swipe(boundary, 1))
    }

    @Test fun `server focus is fallback not local intent`() {
        assertEquals(c, initial.reconcile(listOf(a, b, c), c).current)
        assertEquals(b, initial.choose(b).reconcile(listOf(a, b, c), c).current)
        assertEquals(a, initial.reconcile(listOf(a, c), b).current)
    }

    @Test fun `swipe commits an index only against its original order and version`() {
        assertEquals(c, initial.swipe(initial.boundary(), 2).current)
        val requested = initial.request(a)
        assertEquals(requested, requested.swipe(initial.boundary(), 2))
    }

    @Test fun `old animation completion cannot acknowledge a newer request`() {
        val first = initial.request(a)
        val old = requireNotNull(first.pending)
        val second = first.request(c)
        assertEquals(second, second.complete(old))
        assertEquals(c, second.current)
        assertNull(second.complete(requireNotNull(second.pending)).pending)
    }

    @Test fun `cancelled transition keeps intent until it is completed or replaced`() {
        val requested = initial.request(c)
        val cancelledBoundary = requested.boundary()
        val next = requested.choose(a)
        assertEquals(next, next.complete(requireNotNull(requested.pending)))
        assertEquals(next, next.swipe(cancelledBoundary, 2))
        assertEquals(a, next.current)
        assertNull(next.pending)
    }

    @Test fun `last pane toggles between the two most recent panes`() {
        assertNull(initial.last())
        val chosen = initial.choose(a)
        assertEquals(b, chosen.previous)
        val back = requireNotNull(chosen.last())
        assertEquals(b, back.current)
        assertEquals(a, requireNotNull(back.last()).current)
        assertEquals(b, chosen.choose(a).previous)
        assertEquals(a, chosen.swipe(chosen.boundary(), 2).previous)
    }

    @Test fun `last pane forgets a removed pane`() {
        val chosen = initial.choose(a)
        val removed = chosen.reconcile(listOf(a, c), a)
        assertNull(removed.previous)
        assertNull(removed.last())
    }

    @Test fun `request waits for a split to arrive but a removed target is dropped`() {
        val waiting = initial.reconcile(listOf(a, b), b).request(c)
        assertEquals(c, waiting.pending?.block)
        val arrived = waiting.reconcile(listOf(a, b, c), c)
        assertEquals(c, arrived.current)
        val removed = arrived.reconcile(listOf(a, b), a)
        assertNull(removed.pending)
        assertEquals(a, removed.current)
    }
}
