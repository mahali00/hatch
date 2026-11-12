package dev.mahali.hatch

import org.junit.Assert.assertEquals
import org.junit.Test

class StoresTest {
    @Test fun `saved addresses load in the spelling every connection uses`() {
        assertEquals("https://studio:7000", savedAddress("studio:7000"))
        assertEquals("https://studio.tailnet.ts.net", savedAddress("https://Studio.tailnet.ts.net:443/"))
        assertEquals("ftp://box", savedAddress("ftp://box"))
    }
}
