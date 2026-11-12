package dev.mahali.hatch.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsePairingCodeTest {
    @Test fun acceptsUrls() {
        assertEquals(PairingCode("https://studio.tailnet.ts.net", null), parsePairingCode("https://studio.tailnet.ts.net/"))
        assertEquals(PairingCode("http://100.64.0.1:7000", null), parsePairingCode("http://100.64.0.1:7000"))
        assertEquals(PairingCode("https://studio", null), parsePairingCode("\n HTTPS://STUDIO \n"))
    }

    @Test fun acceptsHostAndPort() {
        assertEquals(PairingCode("https://studio.tailnet.ts.net:8443", null), parsePairingCode(" studio.tailnet.ts.net:8443 "))
        assertEquals(PairingCode("https://studio:7000", null), parsePairingCode("studio:7000"))
        assertEquals(PairingCode("https://[fd7a:115c::1]:7000", null), parsePairingCode("[fd7a:115c::1]:7000"))
    }

    @Test fun acceptsJsonWithUrlAndOptionalName() {
        assertEquals(
            PairingCode("https://box.example.ts.net", "Mac mini"),
            parsePairingCode("""{"url":"https://box.example.ts.net/","name":"  Mac mini "}"""),
        )
        assertEquals(PairingCode("https://box:7000", null), parsePairingCode("""{"url":"box:7000"}"""))
        assertEquals(PairingCode("https://box:7000", null), parsePairingCode("""{"url":"box:7000","name":"  "}"""))
        assertEquals(PairingCode("https://box:7000", null), parsePairingCode("""{"url":"box:7000","name":null}"""))
        assertEquals(PairingCode("https://box:7000", null), parsePairingCode("""{"url":"box:7000","version":2}"""))
    }

    @Test fun rejectsEverythingElse() {
        listOf(
            "", "hello", "hello world", "studio.tailnet.ts.net", "studio:port", "studio:99999",
            "WIFI:S:home;T:WPA;P:secret;;", "mailto:someone@example.com", "ftp://studio:21",
            "https://user:secret@studio", "{\"name\":\"x\"}", "{\"url\":7000}", "{\"url\":\"studio\"}",
            "{\"url\":{\"url\":\"https://studio\"}}", "{not json", "[\"https://studio\"]",
        ).forEach { assertNull(it, parsePairingCode(it)) }
    }
}
