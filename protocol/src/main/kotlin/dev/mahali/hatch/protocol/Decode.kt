package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.BlockInfo
import dev.mahali.hatch.core.BlockedKind
import dev.mahali.hatch.core.HostEvent
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.SessionView
import dev.mahali.hatch.core.StatusRecord
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun List<JsonElement>.objects(): List<JsonObject> = filterIsInstance<JsonObject>()

/** A `session.view` result, or the `view` inside `session.attach`. */
public fun decodeView(o: JsonObject): SessionView = SessionView(
    id = SessionId(o.str("session_id").orEmpty()),
    label = o.str("label").orEmpty(),
    revision = o.long("revision") ?: 0,
    activeWindow = o.str("active_window_id")?.let(::WindowId),
    windows = o.arr("windows").filterIsInstance<JsonObject>().map(::decodeWindow),
)

private fun decodeWindow(w: JsonObject): WindowView {
    val layers = w.arr("layers").filterIsInstance<JsonObject>()
    val tiled = layers.firstOrNull { it.str("kind") == "tiled" }
    val floating = layers.filter { it.str("kind") != "tiled" }
    return WindowView(
        id = WindowId(w.str("window_id").orEmpty()),
        label = w.str("label").orEmpty(),
        layout = tiled?.obj("layout")?.let(::decodeLayout),
        floating = floating.flatMap { layer -> layer.arr("blocks").objects().mapNotNull { it.str("block_id")?.let(::BlockId) } },
        focused = w.str("focused_block_id")?.let(::BlockId),
        blocks = layers.flatMap { it.arr("blocks").objects() }.map { b ->
            BlockInfo(BlockId(b.str("block_id").orEmpty()), b.str("label").orEmpty(), b.str("owner"))
        },
    )
}

/** A layout node: either `block_id`, or `split` with `direction`, `ratio`, `before` and `after`. */
public fun decodeLayout(node: JsonObject): Layout? {
    node.str("block_id")?.let { return Layout.Leaf(BlockId(it)) }
    val split = node.obj("split") ?: return null
    val before = split.obj("before")?.let(::decodeLayout)
    val after = split.obj("after")?.let(::decodeLayout)
    if (before == null || after == null) return before ?: after
    val axis = if (split.str("direction") == "vertical") Axis.Column else Axis.Row
    val ratio = (split["ratio"] as? JsonPrimitive)?.floatOrNull ?: 0.5f
    return Layout.Split(axis, ratio, before, after)
}

public fun decodeStatusRecord(o: JsonObject, id: String = o.str("id").orEmpty()): StatusRecord? {
    val state = ProgramState.parse(o.str("state")) ?: return null
    val active = state == ProgramState.Working || state == ProgramState.Blocked
    val progress = (o["progress"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    return StatusRecord(
        id = id,
        state = state,
        title = o.str("title").orEmpty(),
        message = o.str("msg").orEmpty(),
        progress = progress?.takeIf { active && it in 0..100 },
        updatedAt = o.str("updated_at").orEmpty(),
        app = o.str("app").orEmpty(),
        kind = if (state == ProgramState.Blocked) BlockedKind.parse(o.str("kind")) else null,
        effectiveApp = o.str("effective_app"),
    )
}

/** `{type, payload}` of an `event`. Unknown types decode to null: the protocol says to skip them. */
public fun decodeEvent(event: JsonObject): HostEvent? {
    val p = event.obj("payload") ?: JsonObject(emptyMap())
    return when (event.str("type")) {
        "session_created" -> p.obj("session")?.let { s ->
            s.str("session_id")?.let { HostEvent.SessionCreated(SessionId(it), s.str("label").orEmpty()) }
        }
        "session_destroyed" -> p.str("session_id")?.let { HostEvent.SessionDestroyed(SessionId(it)) }
        "session_moved" -> p.str("session_id")?.let { HostEvent.SessionMoved(SessionId(it), p.str("before_session_id")?.let(::SessionId)) }
        "session_event" -> {
            val session = SessionId(p.str("session_id") ?: return null)
            decodeSessionEvent(session, p.obj("event") ?: return null)
        }
        else -> null
    }
}

private fun decodeSessionEvent(session: SessionId, e: JsonObject): HostEvent? {
    val p = e.obj("payload") ?: JsonObject(emptyMap())
    val block = p.str("block_id")?.let(::BlockId)
    return when (e.str("type")) {
        "session_view_changed" -> HostEvent.ViewChanged(session, p.long("revision") ?: return null)
        "session_label_changed" -> HostEvent.SessionLabelChanged(session, p.str("label").orEmpty())
        "active_window_changed" -> HostEvent.ActiveWindowChanged(session, p.str("window_id")?.let(::WindowId))
        "window_label_changed" -> HostEvent.WindowLabelChanged(session, WindowId(p.str("window_id") ?: return null), p.str("label").orEmpty())
        "block_owner_changed" -> HostEvent.BlockOwnerChanged(block ?: return null, p.str("owner"))
        "block_closed" -> HostEvent.BlockClosed(block ?: return null)
        "block_event" -> decodeBlockEvent(block ?: return null, p.str("name"), p.obj("payload") ?: JsonObject(emptyMap()))
        else -> null
    }
}

private fun decodeBlockEvent(block: BlockId, name: String?, p: JsonObject): HostEvent? = when (name) {
    "title_changed" -> HostEvent.TitleChanged(block, p.str("title").orEmpty())
    "pwd_changed" -> HostEvent.PwdChanged(block, p.str("pwd").orEmpty())
    "process_changed" -> HostEvent.ProcessChanged(block, p.obj("foreground")?.let(::processName).orEmpty())
    "program_status_changed" -> p.obj("record")?.let { decodeStatusRecord(it, p.str("id").orEmpty()) }?.let { HostEvent.StatusChanged(block, it) }
    "program_status_removed" -> HostEvent.StatusRemoved(block, p.arr("ids").mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
    "progress_report" -> HostEvent.ProgressChanged(block, if (p.str("state") == "remove") null else p.int("progress") ?: -1)
    "child_exited" -> HostEvent.ChildExited(block)
    else -> null
}

private val versionName = Regex("[0-9]+(\\.[0-9]+)+")

public fun processName(foreground: JsonObject): String {
    val script = foreground.str("script")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val argv0 = foreground.str("argv0")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    // Claude Code runs as a binary named by its version ("2.1.319"); argv0 still says "claude".
    val name = foreground.str("name")?.takeIf { it.isNotBlank() && !(versionName.matches(it) && argv0 != null) }
    return script ?: name ?: argv0.orEmpty()
}
