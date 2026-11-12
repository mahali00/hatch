package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugInfoTest {
    private val mac = Host(HostId("a"), "MacBook Pro", "https://mac.example.ts.net:8443")
    private val studio = Host(HostId("b"), "Studio", "http://100.64.0.7")

    @Test fun `replaces the full address and the bare machine name`() {
        val text = "failed to reach https://mac.example.ts.net:8443/ws, then MAC.example.ts.net timed out"
        assertEquals("failed to reach MacBook Pro/ws, then MacBook Pro timed out", redactAddresses(text, listOf(mac)))
    }

    @Test fun `replaces an IP address`() =
        assertEquals("Studio refused", redactAddresses("100.64.0.7 refused", listOf(studio)))

    @Test fun `an unparsable address is still replaced verbatim`() {
        val odd = Host(HostId("c"), "Odd", "not a url")
        assertEquals("Odd failed", redactAddresses("not a url failed", listOf(odd)))
    }

    @Test fun `debug info never contains an address`() {
        val state = AppState(
            byHost = mapOf(
                mac.id to HostState(mac, link = Link.Online),
                studio.id to HostState(studio, link = Link.Offline("connect to http://100.64.0.7 failed")),
            ),
        )
        val info = debugInfo(AboutInfo("0.1.0 (1)", "Galaxy Z Fold7 · Android 16", "libghostty-vt f54340ad"), Settings(), listOf(mac, studio), state)
        assertFalse(info.contains("ts.net"))
        assertFalse(info.contains("100.64"))
        assertTrue(info.contains("MacBook Pro: Connected"))
        assertTrue(info.contains("Studio: Offline (connect to Studio failed)"))
        assertTrue(info.startsWith("Hatch 0.1.0 (1)"))
    }
}
