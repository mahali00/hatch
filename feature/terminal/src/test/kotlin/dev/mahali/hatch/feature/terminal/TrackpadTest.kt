package dev.mahali.hatch.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackpadTest {
    @Test fun `slow drags add up to a cell`() {
        val pad = Trackpad(cellWidth = 10f, cellHeight = 20f)
        assertEquals(emptyList<Arrow>(), pad.add(4f, 0f))
        assertEquals(emptyList<Arrow>(), pad.add(4f, 0f))
        assertEquals(listOf(Arrow.Right), pad.add(4f, 0f))
    }

    @Test fun `each axis moves by its own cell size`() =
        assertEquals(listOf(Arrow.Left, Arrow.Left, Arrow.Up), Trackpad(10f, 20f).add(-25f, -30f))

    @Test fun `a fling is capped per event`() = assertEquals(4, Trackpad(10f, 20f).add(500f, 0f).size)
}
