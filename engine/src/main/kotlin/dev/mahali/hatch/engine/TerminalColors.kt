package dev.mahali.hatch.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A terminal theme's colours as `set_theme` takes them: 0xRRGGBB values and a 16-colour palette. */
public data class TerminalColors(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val palette: List<Int>,
    val dark: Boolean,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("foreground", hex(foreground))
        put("background", hex(background))
        put("cursor", hex(cursor))
        put("palette", JsonArray(palette.mapIndexed { i, c -> JsonPrimitive("$i=${hex(c).removePrefix("#")}") }))
        put("scheme", if (dark) "dark" else "light")
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
