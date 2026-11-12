package dev.mahali.hatch.engine

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class StickyDnsTest {
    private val address = InetAddress.getByAddress("mac", byteArrayOf(100, 64, 0, 1))

    @Test fun `a failed lookup falls back to the last good answer`() {
        var up = true
        val dns = StickyDns(Dns { if (up) listOf(address) else throw UnknownHostException(it) })
        assertEquals(listOf(address), dns.lookup("mac.ts.net"))
        up = false
        assertEquals(listOf(address), dns.lookup("mac.ts.net"))
    }

    @Test(expected = UnknownHostException::class)
    fun `a name never resolved still fails`() {
        StickyDns(Dns { throw UnknownHostException(it) }).lookup("new.ts.net")
    }
}
