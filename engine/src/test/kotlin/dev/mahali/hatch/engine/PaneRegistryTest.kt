package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.PanePriority
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.protocol.DataStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaneRegistryTest {
    private val host = HostId("h")
    private val a = PaneKey(host, BlockId("a"))
    private val b = PaneKey(host, BlockId("b"))
    private val c = PaneKey(host, BlockId("c"))
    private val http = OkHttpClient()
    private val streams = CoroutineScope(Job())
    private var created = 0

    @After fun tearDown() = streams.cancel()

    private fun TestScope.registry() = PaneRegistry(backgroundScope, frameCache = null, keepWarmMillis = 1_000, maxWarm = 1)

    private fun PaneRegistry.make(key: PaneKey): PaneAcquisition = acquire(key) {
        created++
        Pane(SessionId("s"), key.block, { sink ->
            DataStream(http, "http://example.invalid", DataStream.TERMINAL, { awaitCancellation() }, sink, streams)
        }, parsing)
    }!!

    @Test fun warmPaneReturnsWithinTheWindowAndClosesAfter() = runTest {
        val panes = registry()
        val first = panes.make(a)
        first.close()
        advanceTimeBy(500)
        val reopened = panes.make(a)
        assertSame(first.pane, reopened.pane)
        assertEquals(PanePriority.Background, reopened.pane.priority)
        assertEquals(listOf(first.pane), panes.shown())

        reopened.close()
        advanceTimeBy(1_001)
        runCurrent()
        assertNotSame(first.pane, panes.make(a).pane)
        assertEquals(2, created)
        panes.close()
    }

    @Test fun beyondMaxWarmTheLongestIdleCloses() = runTest {
        val panes = registry()
        val pa = panes.make(a)
        val pb = panes.make(b)
        val pc = panes.make(c)
        pa.close()
        pb.close()
        assertSame(pb.pane, panes.make(b).pane)
        assertNotSame(pa.pane, panes.make(a).pane)
        assertEquals(4, created)
        assertEquals(3, panes.shown().size)
        assertEquals(true, pc.pane in panes.shown())
        panes.close()
    }
}
