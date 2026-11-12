package dev.mahali.hatch.ghostty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameExchangeTest {
    private fun FrameExchange.publish(cols: Int): FrameBuffer {
        val header = ByteBuffer.allocate(FrameBuffer.HEADER * 4).order(ByteOrder.nativeOrder()).putInt(0, cols).putInt(4, 1)
        return backFrame().also { it.load(header.array()); publish() }
    }

    @Test fun `take returns the newest published frame`() {
        val x = FrameExchange()
        x.publish(10)
        val newest = x.publish(20)
        assertSame(newest, x.take())
        assertEquals(20, x.take()!!.cols)
    }

    @Test fun `take without a new publish returns the held frame`() {
        val x = FrameExchange()
        val first = x.publish(10)
        assertSame(first, x.take())
        assertSame(first, x.take())
        val second = x.publish(20)
        assertSame(second, x.take())
        assertSame(second, x.take())
    }

    @Test fun `the producer never writes the frame the consumer holds`() {
        val x = FrameExchange()
        repeat(50) { i ->
            x.publish(i + 1)
            if (i % 3 == 0) {
                val held = x.take()!!
                assertNotSame(held, x.backFrame())
                x.publish(100 + i)
                assertNotSame(held, x.backFrame())
                assertEquals(100 + i, x.take()!!.cols)
            }
            x.take()?.let { assertNotSame(it, x.backFrame()) }
        }
    }
}
