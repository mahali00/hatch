package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostProbe
import dev.mahali.hatch.core.ProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptConnectTest {
    private val probed = mutableListOf<String>()
    private val reaches = HostProbe { url -> probed += url; ProbeResult.Reached(url, "studio") }
    private val saved = listOf(Host(HostId("1"), "Studio", "https://studio.tailnet.ts.net"))

    private fun attempt(address: String, probe: HostProbe = reaches) = runBlocking { attemptConnect(probe, saved, address) }

    @Test fun probesTheNormalizedAddress() {
        assertEquals(Attempt.Reached("https://box:7000", "studio"), attempt(" box:7000 "))
        assertEquals(listOf("https://box:7000"), probed)
    }

    @Test fun invalidAddressesAreNeverProbed() {
        assertEquals(Attempt.Problem(INVALID_ADDRESS), attempt("ftp://box"))
        assertEquals(emptyList<String>(), probed)
    }

    @Test fun savedHostsAreRecognisedInAnySpelling() {
        assertEquals(Attempt.Problem(ALREADY_SAVED), attempt("studio.tailnet.ts.net:443"))
        assertEquals(emptyList<String>(), probed)
    }

    @Test fun failuresCarryTheCause() {
        val refuses = HostProbe { ProbeResult.Failed("Connection timed out.") }
        assertEquals(Attempt.Problem(COULD_NOT_ADD, "Connection timed out."), attempt("box:7000", refuses))
    }
}
