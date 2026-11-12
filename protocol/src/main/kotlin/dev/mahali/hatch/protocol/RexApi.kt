package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.shellWords
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A key event for `client.key`, in the server's vocabulary (W3C key codes in snake_case). */
public data class KeyEvent(
    val key: String,
    val mods: String = "",
    val consumedMods: String = "",
    val text: String = "",
    val unshiftedCodepoint: Int = 0,
    val action: String = "press",
    val composing: Boolean = false,
)

public class Ticket(public val bytes: ByteArray, public val expiresInMillis: Long)

/** The only place that spells server method names. */
public class RexApi(private val control: RexControl) {
    private companion object {
        const val CHUNK = 900_000
    }

    public suspend fun call(method: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        control.call(method, buildJsonObject(build)).payload

    internal suspend fun terminalReply(session: SessionId, block: BlockId, method: String, args: JsonObjectBuilder.() -> Unit = {}): Reply =
        control.call("com.superlogical.terminal.$method", buildJsonObject {
            put("session_id", session.value)
            put("block_id", block.value)
            put("args", buildJsonObject(args))
        })

    private suspend fun terminal(session: SessionId, block: BlockId, method: String, args: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        terminalReply(session, block, method, args).payload

    public suspend fun createSession(startupCommand: String): SessionId {
        val r = call("session.create") {
            put("initial_windows", JsonArray(listOf(buildJsonObject { put("layout", shellLayout(startupCommand)) })))
        }
        return SessionId(r.str("session_id").orEmpty())
    }

    public suspend fun newWindow(session: SessionId, startupCommand: String): WindowId {
        val r = call("session.new_window") {
            put("session_id", session.value)
            put("layout", shellLayout(startupCommand))
        }
        return WindowId(r.str("window_id").orEmpty())
    }

    public suspend fun newSplit(session: SessionId, anchor: BlockId, startupCommand: String, below: Boolean = false): BlockId? {
        val r = call("session.new_split") {
            put("session_id", session.value)
            put("anchor_id", anchor.value)
            put("direction", if (below) "vertical" else "horizontal")
            put("side", "after")
            put("layout", shellLayout(startupCommand))
            put("focus", true)
        }
        return r.arr("block_ids").firstOrNull()?.let { (it as? JsonPrimitive)?.content }?.let(::BlockId)
    }

    public suspend fun destroySession(session: SessionId) { call("session.destroy") { put("session_id", session.value) } }
    public suspend fun setSessionLabel(session: SessionId, label: String) {
        call("session.set_label") { put("session_id", session.value); put("label", label) }
    }
    public suspend fun setWindowLabel(session: SessionId, window: WindowId, label: String) {
        call("session.set_window_label") { put("session_id", session.value); put("window_id", window.value); put("label", label) }
    }
    public suspend fun closeWindow(session: SessionId, window: WindowId) {
        call("session.close_window") { put("session_id", session.value); put("window_id", window.value) }
    }
    public suspend fun closeBlock(session: SessionId, block: BlockId) {
        call("block.close") { put("session_id", session.value); put("block_id", block.value) }
    }

    /** Detached: outside every window, and [command] runs without a shell. */
    public suspend fun newDetachedTerminal(session: SessionId, label: String, command: List<String>): BlockId {
        val r = call("session.new_block") {
            put("session_id", session.value)
            put("flavor", "com.superlogical.terminal.shell")
            put("label", label)
            putJsonObject("options") {
                put("shell", "none")
                put("command", JsonArray(command.map(::JsonPrimitive)))
            }
        }
        return BlockId(r.str("block_id").orEmpty())
    }

    public suspend fun listBlocks(session: SessionId): List<BlockInfo> =
        call("session.list_blocks") { put("session_id", session.value) }.arr("blocks").objects().map {
            BlockInfo(BlockId(it.str("block_id").orEmpty()), it.str("label").orEmpty(), it.str("placement") == "detached")
        }

    public data class BlockInfo(val id: BlockId, val label: String, val detached: Boolean)

    public suspend fun moveWindow(session: SessionId, window: WindowId, before: WindowId?) {
        call("session.move_window") {
            put("session_id", session.value)
            put("window_id", window.value)
            before?.let { put("before_window_id", it.value) }
        }
    }
    /** The layout arrives as session_view_changed. */
    public suspend fun moveBlock(session: SessionId, block: BlockId, anchor: BlockId, vertical: Boolean, before: Boolean) {
        call("session.move_block") {
            put("session_id", session.value)
            put("block_id", block.value)
            put("anchor_block_id", anchor.value)
            put("direction", if (vertical) "vertical" else "horizontal")
            put("side", if (before) "before" else "after")
        }
    }

    public suspend fun swapBlocks(session: SessionId, block: BlockId, other: BlockId) {
        call("session.swap_blocks") { put("session_id", session.value); put("block_id", block.value); put("other_block_id", other.value) }
    }

    public suspend fun resizePane(session: SessionId, before: BlockId, after: BlockId, axis: Axis, ratio: Float) {
        call("session.resize_pane") {
            put("session_id", session.value)
            put("before_block_id", before.value)
            put("after_block_id", after.value)
            put("direction", if (axis == Axis.Row) "horizontal" else "vertical")
            put("ratio", ratio)
        }
    }
    public suspend fun focusWindow(session: SessionId, window: WindowId) {
        call("session.focus_window") { put("session_id", session.value); put("window_id", window.value) }
    }
    public suspend fun focusBlock(session: SessionId, block: BlockId) {
        call("session.focus_block") { put("session_id", session.value); put("block_id", block.value) }
    }

    public suspend fun ownBlocks(session: SessionId, blocks: List<BlockId>) {
        call("session.own_block") {
            put("session_id", session.value)
            put("block_ids", JsonArray(blocks.map { JsonPrimitive(it.value) }))
            put("mode", "take")
        }
    }
    public suspend fun disownBlocks(session: SessionId, blocks: List<BlockId>) {
        call("session.disown_block") {
            put("session_id", session.value)
            put("block_ids", JsonArray(blocks.map { JsonPrimitive(it.value) }))
        }
    }
    public suspend fun resize(session: SessionId, block: BlockId, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        terminal(session, block, "resize") {
            put("columns", columns); put("rows", rows); put("cell_width_px", cellWidth); put("cell_height_px", cellHeight)
        }
    }

    /** [colors]: `foreground`, `background`, `cursor` as `#RRGGBB`, plus `palette` as `index=RRGGBB` entries. */
    public suspend fun setTheme(session: SessionId, block: BlockId, colors: JsonObject) {
        terminal(session, block, "set_theme") { colors.forEach { (k, v) -> put(k, v) } }
    }

    // key and block.event run in order on the server, so typing and keys never reorder.

    public suspend fun key(session: SessionId, block: BlockId, key: KeyEvent) {
        call("client.key") {
            put("session_id", session.value)
            put("block_id", block.value)
            putJsonObject("key") {
                put("key", key.key)
                put("mods", key.mods)
                put("consumed_mods", key.consumedMods)
                put("text", key.text)
                put("unshifted_codepoint", key.unshiftedCodepoint)
                put("composing", key.composing)
                put("action", key.action)
            }
        }
    }

    public suspend fun blockEvent(session: SessionId, block: BlockId, type: String, payload: JsonObject) {
        call("block.event") {
            put("session_id", session.value)
            put("block_id", block.value)
            putJsonObject("event") { put("type", type); put("payload", payload) }
        }
    }

    public suspend fun paste(session: SessionId, block: BlockId, text: String): Unit =
        blockEvent(session, block, "paste", buildJsonObject { put("text", text) })

    public suspend fun focus(session: SessionId, block: BlockId, focused: Boolean): Unit =
        blockEvent(session, block, "focus", buildJsonObject { put("action", if (focused) "in" else "out") })

    public suspend fun terminalTicket(session: SessionId, block: BlockId): Ticket {
        val r = call("data.connect") {
            put("session_id", session.value)
            put("block_id", block.value)
            put("data_ref_id", "terminal")
            put("subprotocol", DataStream.TERMINAL)
        }
        return Ticket(decodeTicket(r.str("ticket").orEmpty()), r.long("expires_in_ms") ?: 60_000)
    }

    public suspend fun uploadBegin(session: SessionId, block: BlockId, ref: String, name: String, size: Long, batch: Batch?): Ticket {
        val r = terminal(session, block, "upload_begin") {
            put("ref", ref); put("name", name); put("size", size)
            batch?.let { put("batch", it.id); put("index", it.index); put("count", it.count) }
        }
        return Ticket(decodeTicket(r.str("ticket").orEmpty()), r.long("expires_in_ms") ?: 60_000)
    }

    public data class Batch(val id: String, val index: Int, val count: Int)

    public suspend fun title(session: SessionId, block: BlockId): String = terminal(session, block, "title").str("title").orEmpty()
    public suspend fun pwd(session: SessionId, block: BlockId): String = terminal(session, block, "pwd").str("pwd").orEmpty()
    public suspend fun process(session: SessionId, block: BlockId): String =
        terminal(session, block, "process").obj("foreground")?.let(::processName).orEmpty()
    public suspend fun clear(session: SessionId, block: BlockId) { terminal(session, block, "clear") }

    /** Types [text] into the terminal as if the user had typed it, not as a paste. */
    public suspend fun write(session: SessionId, block: BlockId, text: String) { terminal(session, block, "write") { put("data", text) } }

    /** Paths resolve as in the terminal (`~`, relative). status: ok, missing, not_dir or denied. */
    public suspend fun listDir(session: SessionId, block: BlockId, path: String, prefix: String = "", limit: Int = 0): Listing {
        val r = terminal(session, block, "list_dir") {
            put("path", path); put("files", true)
            if (prefix.isNotEmpty()) put("prefix", prefix)
            if (limit > 0) put("limit", limit)
        }
        return Listing(r.str("status").orEmpty(), r.arr("entries").objects().map {
            Listing.Entry(it.str("name").orEmpty(), (it["file"] as? JsonPrimitive)?.content == "true", (it["symlink"] as? JsonPrimitive)?.content == "true")
        })
    }

    public data class Listing(val status: String, val entries: List<Entry>) {
        public data class Entry(val name: String, val file: Boolean, val symlink: Boolean)
    }

    public suspend fun plainText(session: SessionId, block: BlockId): String {
        val first = terminal(session, block, "format") {
            put("format", "text"); put("unwrap", true); put("trim", true); put("max_bytes", CHUNK)
        }
        return first.str("content").orEmpty() + (first.long("id")?.let { readRest(session, block, "blob_read", it, first) }.orEmpty())
    }

    public suspend fun clipboardRead(session: SessionId, block: BlockId, id: Long): String {
        val first = terminal(session, block, "clipboard_read") { put("id", id); put("max_bytes", CHUNK) }
        return first.str("data").orEmpty() + (if (first.eofFalse()) readRest(session, block, "clipboard_read", id, first, "data") else "")
    }

    private suspend fun readRest(
        session: SessionId, block: BlockId, method: String, id: Long, first: JsonObject, field: String = "content",
    ): String {
        val out = StringBuilder()
        var offset = first.str(field).orEmpty().toByteArray().size.toLong()
        while (true) {
            val part = terminal(session, block, method) { put("id", id); put("offset", offset); put("max_bytes", CHUNK) }
            val data = part.str(if (method == "blob_read") "data" else field).orEmpty()
            out.append(data)
            offset += data.toByteArray().size
            if (data.isEmpty() || !part.eofFalse()) return out.toString()
        }
    }

    private fun JsonObject.eofFalse() = (this["eof"] as? JsonPrimitive)?.content == "false"

    public suspend fun serverVersion(): JsonObject = call("server.version")

    public suspend fun setClientLabel(label: String) { call("client.set_label") { put("label", label) } }
}

internal fun shellLayout(startupCommand: String): JsonElement = buildJsonObject {
    putJsonObject("block") {
        put("flavor", "com.superlogical.terminal.shell")
        val argv = shellWords(startupCommand)
        if (argv.isNotEmpty()) putJsonObject("options") { put("command", JsonArray(argv.map(::JsonPrimitive))) }
    }
}

internal fun decodeTicket(base64url: String): ByteArray = java.util.Base64.getUrlDecoder().decode(base64url)
