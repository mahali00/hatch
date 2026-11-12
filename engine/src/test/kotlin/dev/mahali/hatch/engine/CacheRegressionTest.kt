package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheRegressionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `authoritative empty state replaces the old session cache`() {
        val cache = StateCache(folder.newFolder())
        val host = Host(HostId("host"), "Host", "https://example.com")
        val id = SessionId("session")
        cache.save(HostState(host, Link.Online, sessions = listOf(id), sessionsById = mapOf(id to Session(id, "Session"))))
        assertTrue(cache.load(host)!!.sessions.isNotEmpty())
        cache.save(HostState(host, Link.Online))
        assertTrue(cache.load(host)!!.sessions.isEmpty())
    }

    @Test fun `identical block ids on different hosts have independent cached frames`() {
        val cache = FrameCache(folder.newFolder())
        val block = BlockId("same")
        cache.save(HostId("one"), block, byteArrayOf(1, 2))
        cache.save(HostId("two"), block, byteArrayOf(3, 4))
        assertArrayEquals(byteArrayOf(1, 2), cache.load(HostId("one"), block))
        assertArrayEquals(byteArrayOf(3, 4), cache.load(HostId("two"), block))
    }
}
