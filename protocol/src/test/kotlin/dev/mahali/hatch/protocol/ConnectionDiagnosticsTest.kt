package dev.mahali.hatch.protocol

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionDiagnosticsTest {
    @Test fun `pending requests stay visible to collectors without a snapshot copy per request`() = runBlocking {
        val d = ConnectionDiagnostics()
        d.requestStarted(); d.requestStarted()
        assertEquals(2, d.state.value.pendingRequests)
        d.requestFinished()
        assertEquals(1, withTimeout(1000) { d.state.first { it.pendingRequests == 1 } }.pendingRequests)
    }

    @Test fun `connect resume bootstrap and outage are separate measured intervals`() {
        var now = 1000L
        val d = ConnectionDiagnostics({ now }, { now })
        d.connecting(0)
        now += 10
        d.resolved(10, "192.0.2.1")
        now += 30
        d.connected(false)
        d.bootstrap(7)
        assertEquals(40L, d.state.value.connectMillis)
        assertNull(d.state.value.resumeMillis)
        assertEquals(7L, d.state.value.bootstrapMillis)
        d.dropped("control #1 lost")
        now += 100
        d.connecting(1)
        now += 20
        d.connected(true)
        val recovered = d.state.value
        assertEquals(20L, recovered.resumeMillis)
        assertEquals(120L, recovered.lastOutageMillis)
        assertEquals(1, recovered.controlResumes)
        assertEquals("control #1 lost", recovered.lastFailure)
    }

    @Test fun `last successful resume survives a fresh connection and ready has its own duration`() {
        var now = 0L
        val d = ConnectionDiagnostics({ now }, { now })
        d.connecting(0)
        now = 100
        d.connected(true)
        now = 125
        d.attached(20)
        d.ready()
        assertEquals(125L, d.state.value.readyMillis)
        assertEquals(20L, d.state.value.attachMillis)
        d.connecting(0)
        assertNull(d.state.value.readyMillis)
        now = 350
        d.connected(false)
        assertEquals(225L, d.state.value.connectMillis)
        assertEquals(100L, d.state.value.resumeMillis)
    }

    @Test fun `repeated stream waits and successful port probes do not flood history`() {
        val d = ConnectionDiagnostics()
        d.streamWaiting("pane")
        d.streamWaiting("pane")
        assertEquals(1, d.state.value.events.size)
        d.reachability(PortReachability(443, true, 10, 100))
        d.reachability(PortReachability(443, true, 12, 200))
        assertEquals(2, d.state.value.events.size)
        assertEquals(200L, d.state.value.port!!.checkedAtMillis)
        d.reachability(PortReachability(443, false, null, 300, "Refused"))
        assertEquals(3, d.state.value.events.size)
        d.failure("control lost")
        d.reachability(PortReachability(443, true, 9, 400))
        assertEquals("control lost", d.state.value.lastFailure)
    }

    @Test fun `bounded history survives hidden UI and clearing log preserves measurements`() {
        var now = 10L
        val d = ConnectionDiagnostics({ now++ })
        repeat(200) { d.event("event $it"); d.probe(it.toLong()) }
        d.failure("last failure")
        assertEquals(100, d.state.value.events.size)
        assertEquals(30, d.state.value.roundTrips.size)
        assertEquals(199L, d.state.value.roundTrips.last().millis)
        d.clearLog()
        assertTrue(d.state.value.events.isEmpty())
        assertEquals("last failure", d.state.value.lastFailure)
        assertEquals(30, d.state.value.roundTrips.size)
    }
}
