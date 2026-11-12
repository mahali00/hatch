package dev.mahali.hatch.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class TabReorderTest {
    private val h = 100f

    @Test fun `the slot under the held row's centre is the target`() {
        assertEquals(1, reorderTarget(1, 0f, h, 4))
        assertEquals(1, reorderTarget(1, 49f, h, 4))
        assertEquals(2, reorderTarget(1, 51f, h, 4))
        assertEquals(0, reorderTarget(1, -60f, h, 4))
        assertEquals(3, reorderTarget(0, 320f, h, 4))
    }

    @Test fun `past the first or last row it clamps, then leaves the session`() {
        assertEquals(0, reorderTarget(1, -190f, h, 4))
        assertEquals(null, reorderTarget(1, -210f, h, 4))
        assertEquals(3, reorderTarget(2, 190f, h, 4))
        assertEquals(null, reorderTarget(2, 210f, h, 4))
        assertEquals(null, reorderTarget(0, 0f, h, 0))
    }

    @Test fun `rows between the slots step aside towards the empty slot`() {
        assertEquals(listOf(0, -1, -1, 0), (0..3).map { reorderShift(it, 0, 2) })
        assertEquals(listOf(0, 1, 1, 0), (0..3).map { reorderShift(it, 3, 1) })
        assertEquals(listOf(0, 0, 0, 0), (0..3).map { reorderShift(it, 1, null) })
        assertEquals(listOf(0, 0, 0, 0), (0..3).map { reorderShift(it, 1, 1) })
    }

    @Test fun `a move is one before-anchor, or none for last`() {
        val tabs = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), tabs.moved(0, 2))
        assertEquals("d", tabs.beforeOnceMoved(0, 2))
        assertEquals(null, tabs.beforeOnceMoved(0, 3))
        assertEquals("a", tabs.beforeOnceMoved(3, 0))
        assertEquals("b", tabs.beforeOnceMoved(2, 1))
    }

    @Test fun `the list scrolls near its edges, faster closer in`() {
        assertEquals(0f, edgeScrollSpeed(500f, 0f, 1000f, 100f, 10f))
        assertEquals(-5f, edgeScrollSpeed(50f, 0f, 1000f, 100f, 10f))
        assertEquals(-10f, edgeScrollSpeed(-40f, 0f, 1000f, 100f, 10f))
        assertEquals(5f, edgeScrollSpeed(950f, 0f, 1000f, 100f, 10f))
    }
}
