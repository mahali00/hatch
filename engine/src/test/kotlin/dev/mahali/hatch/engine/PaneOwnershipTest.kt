package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.PanePriority
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.protocol.DataStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PaneOwnershipTest {
    @Test fun onlyCurrentVisualAttachmentCanChangePriority() {
        var priority = PanePriority.Background
        val attachments = PaneAttachments { priority = it }
        val first = attachments.attach({}, {}, {})
        assertEquals(PanePriority.Visible, priority)
        first.setPriority(PanePriority.Focused)
        assertEquals(PanePriority.Focused, priority)
        val second = attachments.attach({}, {}, {})
        assertFalse(first.isActive)
        first.setPriority(PanePriority.Background)
        first.close()
        assertEquals(PanePriority.Visible, priority)
        second.setPriority(PanePriority.Focused)
        second.close()
        assertEquals(PanePriority.Background, priority)
        second.setPriority(PanePriority.Visible)
        assertEquals(PanePriority.Background, priority)
    }

    @Test fun replacementDisconnectsBeforeConnectingAndStaleCloseCannotDetachIt() {
        val attachments = PaneAttachments()
        val events = mutableListOf<String>()
        val first = attachments.attach({ events += "connect a" }, { events += "disconnect a" }, { events += "frame a" })
        attachments.notifyFrame()
        val second = attachments.attach({ events += "connect b" }, { events += "disconnect b" }, { events += "frame b" })
        first.close()
        attachments.notifyFrame()
        second.close()
        second.close()
        attachments.notifyFrame()
        assertEquals(listOf("connect a", "frame a", "disconnect a", "connect b", "frame b", "disconnect b"), events)
    }

    @Test fun failedConnectLeavesNoCallbackAndCanBeRetried() {
        val attachments = PaneAttachments()
        var disconnected = 0
        var frames = 0
        try {
            attachments.attach({ error("failed") }, { disconnected++ }, { frames++ })
            fail("expected connect failure")
        } catch (_: IllegalStateException) { }
        attachments.notifyFrame()
        assertEquals(1, disconnected)
        assertEquals(0, frames)
        attachments.attach({}, {}, { frames++ }).use { attachments.notifyFrame() }
        assertEquals(1, frames)
    }

    @Test fun sharedAcquisitionsReleaseIndependentlyAndOnlyLastCloseWarms() = runTest {
        val streams = CoroutineScope(Job())
        val registry = PaneRegistry(backgroundScope, null)
        val key = PaneKey(HostId("h"), BlockId("b"))
        var created = 0
        var warmed = 0
        fun acquire() = registry.acquire(key, { warmed++ }) {
            created++
            Pane(SessionId("s"), key.block, { sink ->
                DataStream(OkHttpClient(), "http://example.invalid", DataStream.TERMINAL, { awaitCancellation() }, sink, streams)
            }, registry.parsing)
        }!!
        try {
            val visual = acquire()
            val search = acquire()
            assertSame(visual.pane, search.pane)
            assertEquals(1, created)
            assertTrue(visual.isActive)
            assertEquals(PanePriority.Background, visual.pane.priority)
            var disconnected = 0
            visual.attach({}, { disconnected++ }, {}).setPriority(PanePriority.Focused)
            val searchOnly = acquire()
            assertSame(visual.pane, searchOnly.pane)
            assertEquals(PanePriority.Focused, visual.pane.priority)
            searchOnly.close()
            assertEquals(PanePriority.Focused, visual.pane.priority)
            search.attach({}, { disconnected++ }, {})
            assertEquals(1, disconnected)
            visual.close()
            visual.close()
            assertFalse(visual.isActive)
            assertEquals(1, disconnected)
            assertEquals(0, warmed)
            assertEquals(listOf(search.pane), registry.shown())
            search.close()
            search.close()
            assertEquals(2, disconnected)
            assertEquals(1, warmed)
            assertTrue(registry.shown().isEmpty())
            try {
                visual.attach({}, {}, {})
                fail("closed acquisition attached")
            } catch (_: IllegalStateException) { }
        } finally { registry.close(); streams.cancel() }
    }

    @Test fun closingHostInvalidatesOutstandingAcquisitionAndAttachment() = runTest {
        val streams = CoroutineScope(Job())
        val registry = PaneRegistry(backgroundScope, null)
        val host = HostId("closed")
        val acquisition = registry.acquire(PaneKey(host, BlockId("b"))) {
            Pane(SessionId("s"), BlockId("b"), { sink ->
                DataStream(OkHttpClient(), "http://example.invalid", DataStream.TERMINAL, { awaitCancellation() }, sink, streams)
            }, registry.parsing)
        }!!
        try {
            val attachment = acquisition.attach({}, {}, {})
            attachment.setPriority(PanePriority.Focused)
            registry.closeHost(host)
            assertFalse(acquisition.isActive)
            assertFalse(acquisition.pane.isActive)
            assertFalse(attachment.isActive)
            attachment.setPriority(PanePriority.Focused)
            assertEquals(PanePriority.Background, acquisition.pane.priority)
            try {
                acquisition.attach({}, {}, {})
                fail("closed host runtime attached")
            } catch (_: IllegalStateException) { }
            acquisition.close()
            registry.close()
            assertNull(registry.acquire(PaneKey(host, BlockId("new"))) { error("closed registry created a pane") })
        } finally { registry.close(); streams.cancel() }
    }
}
