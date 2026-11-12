package dev.mahali.hatch.design

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleAddressTest {
    @Test fun `MagicDNS names and the 100_64 range are Tailscale`() {
        assertTrue(isTailscaleAddress("https://rex.example.ts.net"))
        assertTrue(isTailscaleAddress("box.example.ts.net:8443"))
        assertTrue(isTailscaleAddress("http://100.101.102.103:7777"))
        assertTrue(isTailscaleAddress("100.64.0.1"))
    }

    @Test fun `everything else is not`() {
        assertFalse(isTailscaleAddress("https://example.com"))
        assertFalse(isTailscaleAddress("http://192.168.1.20:7777"))
        assertFalse(isTailscaleAddress("100.128.0.1"))
        assertFalse(isTailscaleAddress("http://notts.net"))
        assertFalse(isTailscaleAddress(""))
    }
}
