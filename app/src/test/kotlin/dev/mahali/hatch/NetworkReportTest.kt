package dev.mahali.hatch

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.engine.InputLoad
import dev.mahali.hatch.protocol.ConnectionSnapshot
import dev.mahali.hatch.protocol.DiagnosticEvent
import dev.mahali.hatch.protocol.PortReachability
import dev.mahali.hatch.protocol.RoundTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReportTest {
    private val a = HostId("a")
    private val b = HostId("b")
    private val state = AppState(listOf(a, b), mapOf(
        a to HostState(Host(a, "Laptop", "https://example.com"), Link.Online),
        b to HostState(Host(b, "Homelab", "https://example.org"), Link.Offline("lost")),
    ))

    @Test fun `report includes both hosts actual usage timings reachability and failures`() {
        val network = NetworkPathSnapshot("Wi-Fi + VPN · wlan0 Wi-Fi (available) · tun0 tunnel · IPv4", 100)
        val diags = mapOf(a to ConnectionSnapshot(connectMillis = 155, resumeMillis = 155, bootstrapMillis = 8, attachMillis = 20, readyMillis = 175,
            roundTrips = listOf(RoundTrip(23, 1000)), lastFailure = "Control lost", port = PortReachability(443, true, 9, 1000),
            liveStreams = setOf("one")), b to ConnectionSnapshot(waitingStreams = setOf("two")))
        val loads = mapOf(a to InputLoad(2, 3, 100), b to InputLoad())
        val report = networkReport(network, state, diags, loads, emptyList())
        assertTrue(report.startsWith("Path: Wi-Fi + VPN\n  wlan0 Wi-Fi (available) · tun0 tunnel · IPv4"))
        assertTrue(report.contains("Laptop: ready\n  rtt 23 ms · connect 155 ms · resume 155 ms · bootstrap 8 ms · attach 20 ms · TCP port 443 reachable"))
        assertTrue(report.contains("last failure: Control lost"))
        assertTrue(report.contains("Homelab: offline"))
        assertTrue(report.contains("Input budget 3 of 64 calls in flight · 2 batches retained"))
        assertTrue(report.contains("Terminal streams 1 live, 1 waiting"))
        assertFalse(report.contains("unmeasured ms"))
        assertFalse(report.contains("null"))
    }

    @Test fun `bootstrapped control is attaching until the views are ready`() {
        assertEquals("attaching", diagnosticState(Link.Online, ConnectionSnapshot()))
        assertEquals("ready", diagnosticState(Link.Online, ConnectionSnapshot(readyMillis = 100)))
    }

    @Test fun `events within one millisecond retain their recorded order in the report`() {
        val path = NetworkPathSnapshot("Wi-Fi", 1)
        val diags = mapOf(a to ConnectionSnapshot(events = listOf(DiagnosticEvent(100, "ready"), DiagnosticEvent(100, "bootstrap"))))
        val report = networkReport(path, state, diags, emptyMap(), diagnosticLog(state, diags, path))
        assertTrue(report.indexOf("Laptop bootstrap") < report.indexOf("Laptop ready"))
    }

    @Test fun `app lifecycle events stay global and appear once in the report`() {
        val path = NetworkPathSnapshot("Wi-Fi", 1, events = listOf(
            DiagnosticEvent(300, "App active"), DiagnosticEvent(200, "App inactive"), DiagnosticEvent(100, "App active"),
        ))
        val report = networkReport(path, state, emptyMap(), emptyMap(), diagnosticLog(state, emptyMap(), path))
        assertEquals(2, Regex("App active").findAll(report).count())
        assertEquals(1, Regex("App inactive").findAll(report).count())
        assertFalse(report.contains("Laptop App"))
        assertFalse(report.contains("Homelab App"))
        assertTrue(report.indexOf("App active") < report.indexOf("App inactive"))
        assertTrue(report.indexOf("App inactive") < report.lastIndexOf("App active"))
    }

    @Test fun `path history is global and exported events are chronological with host names`() {
        val path = NetworkPathSnapshot("Wi-Fi", 1, events = listOf(DiagnosticEvent(100, "Path Wi-Fi")))
        val diags = mapOf(a to ConnectionSnapshot(events = listOf(DiagnosticEvent(300, "ready"))),
            b to ConnectionSnapshot(events = listOf(DiagnosticEvent(200, "connecting"))))
        val log = diagnosticLog(state, diags, path)
        assertEquals(listOf(300L, 200L, 100L), log.map { it.atMillis })
        val report = networkReport(path, state, diags, emptyMap(), log)
        assertTrue(report.indexOf("Path Wi-Fi") < report.indexOf("Homelab connecting"))
        assertTrue(report.indexOf("Homelab connecting") < report.indexOf("Laptop ready"))
        assertEquals(1, Regex("Path Wi-Fi").findAll(report).count())
    }
}
