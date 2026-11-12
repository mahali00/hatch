package dev.mahali.hatch.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** A message the server embeds in a pane's byte stream as `rex1;<nonce>;<json>`. */
public sealed interface InBandMessage {
    public data class Resized(val cols: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int) : InBandMessage

    public data class Themed(val foreground: Int, val background: Int, val cursor: Int, val palette: List<Pair<Int, Int>>) : InBandMessage

    public data class Exited(val exitCode: Int?, val error: String) : InBandMessage
}

/** A message with another [nonce] was printed by a program, not the server: decodes to null. */
public fun decodeInBand(raw: String, nonce: Long): InBandMessage? {
    val parts = raw.split(';', limit = 3)
    if (parts.size != 3 || parts[1].toULongOrNull()?.toLong() != nonce) return null
    val msg = runCatching { Json.parseToJsonElement(parts[2]).jsonObject }.getOrNull() ?: return null
    return when (msg.str("type")) {
        "resize" -> InBandMessage.Resized(
            msg.int("cols") ?: return null, msg.int("rows") ?: return null, msg.int("cell_width_px") ?: 0, msg.int("cell_height_px") ?: 0,
        )
        "theme" -> InBandMessage.Themed(
            hex(msg.str("foreground")), hex(msg.str("background")), hex(msg.str("cursor")),
            msg.arr("palette").mapNotNull { entry ->
                val (index, color) = (entry as? JsonPrimitive)?.contentOrNull?.split('=', limit = 2)?.takeIf { it.size == 2 } ?: return@mapNotNull null
                val i = index.toIntOrNull()
                val rgb = hex(color)
                if (i != null && i in 0..255 && rgb >= 0) i to rgb else null
            },
        )
        "child_exited" -> InBandMessage.Exited(msg.int("exit_code")?.takeIf { it >= 0 }, msg.str("error").orEmpty())
        else -> null
    }
}

private fun hex(value: String?): Int = value?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: -1
