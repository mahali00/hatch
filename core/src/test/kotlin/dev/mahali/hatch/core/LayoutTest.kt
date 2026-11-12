package dev.mahali.hatch.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutTest {
    private val a = BlockId("block:a")
    private val b = BlockId("block:b")
    private val c = BlockId("block:c")

    private val tree = Layout.Split(
        Axis.Row, 0.6f,
        Layout.Leaf(a),
        Layout.Split(Axis.Column, 0.5f, Layout.Leaf(b), Layout.Leaf(c)),
    )

    @Test fun `blocks come in reading order`() {
        assertEquals(listOf(a, b, c), tree.blocks())
    }

    @Test fun `splits divide space by ratio and axis`() {
        val r = tree.rects()
        assertEquals(Rect(0f, 0f, 0.6f, 1f), r[a])
        assertEquals(Rect(0.6f, 0f, 0.39999998f, 0.5f), r[b])
        assertEquals(Rect(0.6f, 0.5f, 0.39999998f, 0.5f), r[c])
    }
}
