package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.core.normalizeAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeAddressTest {
    private fun ok(input: String) = normalizeAddress(input).getOrThrow()

    @Test fun bareHostsGetHttps() {
        assertEquals("https://studio.tailnet.ts.net", ok("studio.tailnet.ts.net"))
        assertEquals("https://studio", ok("studio"))
        assertEquals("https://studio:7000", ok("studio:7000"))
        assertEquals("https://100.64.0.1:8443", ok("100.64.0.1:8443"))
        assertEquals("https://[fd7a:115c::1]:7000", ok("[fd7a:115c::1]:7000"))
    }

    @Test fun keepsHttpAndHttps() {
        assertEquals("http://100.64.0.1:7000", ok("http://100.64.0.1:7000"))
        assertEquals("https://box.example.ts.net", ok("https://box.example.ts.net"))
    }

    @Test fun oneServerHasOneSpelling() {
        assertEquals("https://studio.tailnet.ts.net", ok("  HTTPS://Studio.Tailnet.TS.net/  "))
        assertEquals("https://studio", ok("https://studio:443"))
        assertEquals("http://studio", ok("http://studio:80"))
        assertEquals("http://studio:443", ok("http://studio:443"))
        assertEquals("https://studio/rex", ok("https://studio/rex/"))
    }

    @Test fun rejectsWhatCannotBeAServerAddress() {
        listOf(
            "", "   ", "ftp://studio", "ws://studio", "studio name", "https://", "http://:7000",
            "https://user:secret@studio", "https://studio?x=1", "https://studio#top",
            "studio:0", "studio:65536", "studio:port", "mailto:someone@example.com",
        ).forEach { input ->
            val error = normalizeAddress(input).exceptionOrNull()
            assertEquals(input, INVALID_ADDRESS, error?.message)
        }
    }

}
