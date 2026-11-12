package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

public data class ForegroundProcess(
    public val pid: Long,
    public val name: String,
    public val path: String,
    public val invokedPath: String,
    public val script: String,
    public val argv0: String,
)

public data class TerminalIdentity(
    public val columns: Int,
    public val rows: Int,
    public val foreground: ForegroundProcess,
    public val alternate: Boolean,
    public val modes: Set<String>,
)

public data class PromptScreenExport(public val identity: TerminalIdentity, public val vt: String)
public class PromptScreenUnavailable(public val reason: String) : Exception(reason)

private fun JsonObject.foreground(): ForegroundProcess {
    val p = obj("foreground") ?: throw PromptScreenUnavailable("process")
    val pid = p.long("pid") ?: throw PromptScreenUnavailable("process")
    if (pid <= 0) throw PromptScreenUnavailable("process")
    val name = p.str("name")?.takeIf { it.isNotBlank() } ?: throw PromptScreenUnavailable("process")
    return ForegroundProcess(pid, name, p.str("path").orEmpty(), p.str("invoked_path").orEmpty(),
        p.str("script").orEmpty(), p.str("argv0").orEmpty())
}

public suspend fun RexApi.foregroundProcess(session: SessionId, block: BlockId): ForegroundProcess =
    terminalReply(session, block, "process").payload.foreground()

/** Never treats a truncated head or tail as a screen. */
public suspend fun RexApi.promptScreen(
    session: SessionId,
    block: BlockId,
    maxBytes: Int = 256 * 1024,
    timeoutMillis: Long = 2_000,
): PromptScreenExport = withTimeout(timeoutMillis) {
    require(maxBytes in 1..256 * 1024)
    suspend fun terminal(method: String, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        terminalReply(session, block, method, args).payload
    suspend fun identity(): TerminalIdentity = coroutineScope {
        val sizeRead = async { terminal("size") }
        val processRead = async { terminal("process") }
        val modesRead = async { terminal("modes") }
        val size = sizeRead.await()
        val columns = size.int("columns") ?: throw PromptScreenUnavailable("size")
        val rows = size.int("rows") ?: throw PromptScreenUnavailable("size")
        if (columns !in 1..320 || rows !in 1..120) throw PromptScreenUnavailable("size")
        val process = processRead.await().foreground()
        val modes = modesRead.await()
        val screen = modes.str("screen")
        if (screen != "primary" && screen != "alternate") throw PromptScreenUnavailable("modes")
        TerminalIdentity(columns, rows, process, screen == "alternate",
            modes.arr("modes").objects().filter { (it["set"] as? JsonPrimitive)?.booleanOrNull == true }
                .map { "${it.str("name")}:${it.int("number")}:${it["ansi"]}" }.toSet())
    }
    val before = identity()
    val first = terminal("format") {
        put("format", "vt"); put("trim", false); put("unwrap", false)
        put("extra_cursor", true); put("extra_modes", true); put("extra_style", true)
        put("extra_charsets", true); put("extra_scrolling_region", true); put("extra_tabstops", true)
        put("max_bytes", minOf(maxBytes, 32 * 1024))
    }
    val firstText = first.str("content") ?: throw PromptScreenUnavailable("content")
    val output = StringBuilder(firstText)
    var offset = firstText.toByteArray().size.toLong()
    val total = first.long("total")
    if (offset > maxBytes || total != null && (total < offset || total > maxBytes)) throw PromptScreenUnavailable("too_large")
    val id = first.long("id")
    if (id != null) {
        if (total == null) throw PromptScreenUnavailable("incomplete")
        var complete = false
        while (offset < total) {
            val part = terminal("blob_read") {
                put("id", id); put("offset", offset); put("max_bytes", minOf(32 * 1024L, maxBytes - offset))
            }
            val text = part.str("data") ?: throw PromptScreenUnavailable("incomplete")
            val bytes = text.toByteArray().size
            if (bytes == 0 || bytes > maxBytes - offset || bytes > total - offset) throw PromptScreenUnavailable("incomplete")
            output.append(text)
            offset += bytes
            complete = (part["eof"] as? JsonPrimitive)?.booleanOrNull ?: throw PromptScreenUnavailable("incomplete")
            if (complete) break
        }
        if (!complete || offset != total) throw PromptScreenUnavailable("incomplete")
    } else if (total != null && total != offset) throw PromptScreenUnavailable("incomplete")
    if (identity() != before) throw PromptScreenUnavailable("changed")
    PromptScreenExport(before, output.toString())
}
