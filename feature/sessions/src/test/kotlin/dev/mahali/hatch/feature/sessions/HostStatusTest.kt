package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostStatusTest {
    @Test fun `notices follow the link and the cache`() {
        val withSessions = host(session("s1", "a") to emptyList())
        assertEquals(HostNotice.Loading, host(link = Link.Connecting()).notice())
        assertEquals(HostNotice.Empty, host().notice())
        assertNull(withSessions.notice())
        assertNull(withSessions.copy(link = Link.Connecting()).notice())
        assertEquals(HostNotice.Offline, withSessions.copy(link = Link.Offline("refused")).notice())
        assertEquals(HostNotice.Replaced, withSessions.copy(replacedServer = true).notice())
    }

    @Test fun `timestamps parse with any offset and reject junk`() {
        assertEquals(parseInstantMillis("2026-11-09T10:00:00Z"), parseInstantMillis("2026-11-09T11:00:00+01:00"))
        assertEquals(1_000L, parseInstantMillis("1970-01-01T00:00:01.000Z"))
        assertNull(parseInstantMillis(""))
        assertNull(parseInstantMillis("yesterday"))
    }

    @Test fun `collapse toggles one session and collapse others keeps unloaded entries`() {
        val a = SessionId("a")
        assertEquals(setOf("a"), emptySet<String>().toggled(a))
        assertEquals(emptySet<String>(), setOf("a").toggled(a))
        val state = host(session("a", "a") to emptyList(), session("b", "b") to emptyList()).app()
        assertEquals(setOf("b", "elsewhere"), setOf("a", "elsewhere").collapsingAllBut(a, state))
    }
}
