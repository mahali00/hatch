package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.SessionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockEventDecodeTest {
    private val s = SessionId("s")
    private val b = BlockId("b")
    private fun signal(name: String, payload: String = "{}") = BlockSignal(s, b, name, Json.parseToJsonElement(payload).jsonObject).toEvent()

    @Test fun `a status change carries the record and its child id`() {
        val e = signal("program_status_changed", """{"id":"task","record":{"state":"done","title":"Build"}}""") as BlockEvent.StatusChanged
        assertEquals("task", e.record.id)
        assertEquals(ProgramState.Done, e.record.state)
        assertEquals("Build", e.record.title)
        assertEquals("", (signal("program_status_changed", """{"record":{"state":"idle"}}""") as BlockEvent.StatusChanged).record.id)
    }

    @Test fun `a status change without a valid record is dropped`() {
        assertNull(signal("program_status_changed"))
        assertNull(signal("program_status_changed", """{"record":{"state":"new-state"}}"""))
        assertNull(signal("program_status_changed", """{"record":"done"}"""))
    }

    @Test fun `removed ids skip what is not a string`() {
        assertEquals(BlockEvent.StatusRemoved(s, b, setOf("a", "1")), signal("program_status_removed", """{"ids":["a",1,null,{}]}"""))
        assertEquals(BlockEvent.StatusRemoved(s, b, emptySet()), signal("program_status_removed"))
    }

    @Test fun `clipboard text comes inline or by id`() {
        assertEquals(BlockEvent.ClipboardWritten(s, b, "copied", null, 0), signal("clipboard_written", """{"text":"copied"}"""))
        assertEquals(BlockEvent.ClipboardWritten(s, b, null, 7, 4096), signal("clipboard_written", """{"id":7,"bytes":4096}"""))
        assertNull(signal("clipboard_written", """{"bytes":4096}"""))
    }

    @Test fun `urls, bells and unknown names`() {
        assertEquals(BlockEvent.UrlClicked(s, b, "https://x"), signal("url_clicked", """{"url":"https://x"}"""))
        assertNull(signal("url_clicked"))
        assertEquals(BlockEvent.Bell(s, b), signal("bell"))
        assertNull(signal("future_signal"))
    }
}
