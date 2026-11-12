package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.Block
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Saved host state for a cold start. Sessions use revision -1 so any server view replaces them. */
public object StateCodec {
    public fun encode(state: HostState): String = buildJsonObject {
        put("v", 1)
        state.serverId?.let { put("server", it) }
        put("sessions", buildJsonArray {
            for (id in state.sessions) {
                val s = state.sessionsById[id] ?: continue
                add(buildJsonObject {
                    put("id", id.value); put("label", s.label)
                    s.activeWindow?.let { put("active", it.value) }
                    put("windows", JsonArray(s.windows.map { JsonPrimitive(it.value) }))
                })
            }
        })
        put("windows", buildJsonArray {
            for (w in state.windowsById.values) add(buildJsonObject {
                put("id", w.id.value); put("label", w.label)
                w.layout?.let { put("layout", layout(it)) }
                put("floating", JsonArray(w.floating.map { JsonPrimitive(it.value) }))
                w.focused?.let { put("focused", it.value) }
            })
        })
        put("blocks", buildJsonArray {
            for (b in state.blocksById.values) add(buildJsonObject {
                put("id", b.id.value); put("label", b.label); put("title", b.title)
                put("pwd", b.workingDirectory); put("process", b.processName); put("program", b.program.value)
                if (b.exited) put("exited", true)
            })
        })
    }.toString()

    public fun decode(host: Host, text: String): HostState? = runCatching {
        val o = ProtocolJson.parseToJsonElement(text).jsonObject
        if (o.str("v") != "1") return null
        val sessions = o.arr("sessions").map { it.jsonObject }.map { s ->
            Session(
                SessionId(s.str("id")!!), s.str("label").orEmpty(), revision = -1,
                windows = s.arr("windows").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::WindowId) },
                activeWindow = s.str("active")?.let(::WindowId),
            )
        }
        val windows = o.arr("windows").map { it.jsonObject }.map { w ->
            Window(
                WindowId(w.str("id")!!), w.str("label").orEmpty(), (w["layout"] as? JsonObject)?.let(::layout),
                floating = w.arr("floating").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::BlockId) },
                focused = w.str("focused")?.let(::BlockId),
            )
        }
        val blocks = o.arr("blocks").map { it.jsonObject }.map { b ->
            Block(
                BlockId(b.str("id")!!), label = b.str("label").orEmpty(), title = b.str("title").orEmpty(),
                workingDirectory = b.str("pwd").orEmpty(), processName = b.str("process").orEmpty(),
                program = ProgramKey(b.str("program") ?: ProgramKey.Terminal.value),
                exited = (b["exited"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        HostState(
            host = host, link = Link.Connecting(), serverId = o.str("server"),
            sessions = sessions.map { it.id }, sessionsById = sessions.associateBy { it.id },
            windowsById = windows.associateBy { it.id }, blocksById = blocks.associateBy { it.id },
        )
    }.getOrNull()

    private fun layout(l: Layout): JsonObject = when (l) {
        is Layout.Leaf -> buildJsonObject { put("leaf", l.block.value) }
        is Layout.Split -> buildJsonObject {
            put("axis", l.axis.name); put("ratio", l.ratio); put("before", layout(l.before)); put("after", layout(l.after))
        }
    }

    private fun layout(o: JsonObject): Layout = o.str("leaf")?.let { Layout.Leaf(BlockId(it)) } ?: Layout.Split(
        Axis.valueOf(o.str("axis")!!), (o["ratio"] as JsonPrimitive).floatOrNull ?: 0.5f,
        layout(o["before"]!!.jsonObject), layout(o["after"]!!.jsonObject),
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray)?.jsonArray.orEmpty()
}
