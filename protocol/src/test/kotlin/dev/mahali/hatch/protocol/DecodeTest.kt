package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostEvent
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.BlockedKind
import dev.mahali.hatch.core.SessionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodeTest {
    private val viewExample: JsonObject by lazy {
        Json.parseToJsonElement(
            """
            {"revision":7,"session_id":"session:<uuid>","label":"work","active_window_id":"window:<uuid>",
             "windows":[{"window_id":"window:<uuid>","label":"editor",
               "focused_block_id":"block:01234567-89ab-cdef-0123-456789abcde1",
               "layers":[{"layer_id":"layer:<uuid>","kind":"tiled","bounds":[0,0,1,1],
                 "layout":{"rect":[0,0,1,1],"split":{"split_id":"split:<uuid>","direction":"horizontal","ratio":0.5,
                   "before":{"rect":[0,0,0.5,1],"block_id":"block:01234567-89ab-cdef-0123-456789abcde1"},
                   "after":{"rect":[0.5,0,0.5,1],"block_id":"block:01234567-89ab-cdef-0123-456789abcde2"}}},
                 "blocks":[
                   {"block_id":"block:01234567-89ab-cdef-0123-456789abcde1","label":"shell","creator_name":"com.superlogical.terminal","flavor":"com.superlogical.terminal.shell","host_id":"0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10","owner":"client:<uuid>"},
                   {"block_id":"block:01234567-89ab-cdef-0123-456789abcde2","label":"logs","creator_name":"com.superlogical.terminal","flavor":"com.superlogical.terminal.shell","host_id":"0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10","owner":"client:<uuid>"}
                 ]}]}]}
            """.trimIndent(),
        ).jsonObject
    }

    @Test fun `the documented view decodes into a split tree`() {
        val view = decodeView(viewExample)
        assertEquals(7L, view.revision)
        val window = view.windows.single()
        val a = BlockId("block:01234567-89ab-cdef-0123-456789abcde1")
        val b = BlockId("block:01234567-89ab-cdef-0123-456789abcde2")
        assertEquals(Layout.Split(Axis.Row, 0.5f, Layout.Leaf(a), Layout.Leaf(b)), window.layout)
        assertEquals(a, window.focused)
        assertEquals(listOf("shell", "logs"), window.blocks.map { it.label })
    }

    private fun event(json: String) = decodeEvent(Json.parseToJsonElement(json).jsonObject)
    private fun record(json: String) = decodeStatusRecord(Json.parseToJsonElement(json).jsonObject)

    @Test fun `missing unknown and clear states are not converted into idle records`() {
        for (json in listOf("{}", """{"state":"new-state"}""", """{"state":"clear"}""")) assertNull(record(json))
        assertEquals(ProgramState.Idle, record("""{"state":"idle"}""")!!.state)
        assertNull(event("""{"type":"session_event","payload":{"session_id":"s","event":{"type":"block_event","payload":{"block_id":"b","name":"program_status_changed","payload":{"record":{"state":"new-state"}}}}}}"""))
    }

    @Test fun `blocked kinds and decoded messages survive the server boundary`() {
        for ((wire, kind) in listOf("permission" to BlockedKind.Permission, "question" to BlockedKind.Question, "auth" to BlockedKind.Auth)) {
            val r = record("""{"state":"blocked","id":"build/test","kind":"$wire","msg":"Allow this task?","progress":37,"app":"worker","effective_app":"worker"}""")!!
            assertEquals(kind, r.kind)
            assertEquals("build/test", r.id)
            assertEquals("Allow this task?", r.message)
            assertEquals(37, r.progress)
            assertEquals("worker", r.app)
            assertEquals("worker", r.effectiveApp)
        }
        assertNull(record("""{"state":"blocked","kind":"future"}""")!!.kind)
        assertNull(record("""{"state":"working","kind":"permission"}""")!!.kind)
    }

    @Test fun `only active records have integer percentages from zero through one hundred`() {
        for (value in listOf("-1", "101", "2.5", "\"37\"", "null")) {
            assertNull(record("""{"state":"working","progress":$value}""")!!.progress)
        }
        for (value in listOf(0, 37, 100)) assertEquals(value, record("""{"state":"working","progress":$value}""")!!.progress)
        for (state in listOf("idle", "done", "error")) assertNull(record("""{"state":"$state","progress":37}""")!!.progress)
    }

    @Test fun `session and block events decode`() {
        assertEquals(
            HostEvent.ViewChanged(SessionId("session:1"), 9),
            event("""{"type":"session_event","payload":{"session_id":"session:1","event":{"type":"session_view_changed","payload":{"revision":9}}}}"""),
        )
        val status = event(
            """{"type":"session_event","payload":{"session_id":"session:1","event":{"type":"block_event","payload":
            {"block_id":"block:a","name":"program_status_changed","payload":{"id":"","record":{"state":"blocked","msg":"Allow?"}}}}}}""",
        ) as HostEvent.StatusChanged
        assertEquals(ProgramState.Blocked, status.record.state)
        assertEquals("Allow?", status.record.message)
    }

    @Test fun `events the app does not use are skipped`() {
        assertNull(event("""{"type":"client_joined","payload":{}}"""))
        assertNull(event("""{"type":"session_event","payload":{"session_id":"s","event":{"type":"brand_new_event"}}}"""))
    }

    @Test fun `a script's program wins over its interpreter`() {
        val fg = Json.parseToJsonElement("""{"name":"node","argv0":"node","script":"/opt/bin/claude"}""").jsonObject
        assertEquals("claude", processName(fg))
    }

    @Test fun `a process named after its version is called by argv0`() {
        val fg = kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive("2.1.319"))
            put("argv0", kotlinx.serialization.json.JsonPrimitive("claude"))
        }
        org.junit.Assert.assertEquals("claude", processName(fg))
    }
}
