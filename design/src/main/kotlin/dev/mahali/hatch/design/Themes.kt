package dev.mahali.hatch.design

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public data class TerminalTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val ansi: List<Int>,
    private val ui: Map<String, Int>,
    val extended: List<Int> = emptyList(),
) {
    public fun token(name: String): Int? = ui[name]
}

public object Themes {
    @Serializable internal data class TerminalJson(val background: String, val foreground: String, val cursor: String, val palette: List<String>)
    @Serializable internal data class ThemeJson(val id: String, val name: String, val dark: Boolean, val terminal: TerminalJson, val ui: Map<String, String> = emptyMap())

    private fun ThemeJson.toTheme() = TerminalTheme(
        id = id,
        name = name,
        dark = dark,
        foreground = argb(terminal.foreground),
        background = argb(terminal.background),
        cursor = argb(terminal.cursor),
        ansi = terminal.palette.take(16).map(::argb),
        ui = ui.mapValues { argb(it.value) },
        extended = terminal.palette.drop(16).map(::argb),
    )

    @Volatile private var cached: List<TerminalTheme>? = null

    public const val DEFAULT_LIGHT: String = "merino-light"
    public const val DEFAULT_DARK: String = "oxblood-library"

    public fun all(context: Context): List<TerminalTheme> = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    public fun find(context: Context, id: String, dark: Boolean): TerminalTheme {
        val themes = all(context)
        return themes.firstOrNull { it.id == id && it.dark == dark }
            ?: themes.first { it.id == if (dark) DEFAULT_DARK else DEFAULT_LIGHT }
    }

    private fun load(context: Context): List<TerminalTheme> =
        parse(context.assets.open("themes.json").bufferedReader().use { it.readText() })

    internal fun parse(json: String): List<TerminalTheme> =
        ThemeFormat.decodeFromString<List<ThemeJson>>(json).map { it.toTheme() }.sortedBy { it.name }

    internal fun argb(hex: String): Int {
        val h = hex.removePrefix("#")
        return when (h.length) {
            8 -> h.toLong(16).toInt()
            6 -> (0xFF000000 or h.toLong(16)).toInt()
            else -> 0xFF000000.toInt()
        }
    }
}

private val ThemeFormat = Json { ignoreUnknownKeys = true }
