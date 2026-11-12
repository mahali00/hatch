package dev.mahali.hatch

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostKind
import dev.mahali.hatch.core.HostStore
import dev.mahali.hatch.core.KeyBarKey
import dev.mahali.hatch.core.ShortcutGroup
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.core.normalizeAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class PrefsHostStore(context: Context) : HostStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val hosts: StateFlow<List<Host>> get() = state

    override fun save(host: Host) {
        val saved = host.copy(url = savedAddress(host.url))
        val list = state.value
        write(if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved)
    }

    override fun remove(id: HostId) = write(state.value.filterNot { it.id == id })

    override fun reorder(ids: List<HostId>) {
        val byId = state.value.associateBy { it.id }
        write(ids.mapNotNull { byId[it] } + state.value.filter { it.id !in ids })
    }

    private fun write(hosts: List<Host>) {
        state.value = hosts
        val json = JSONArray(hosts.map { h ->
            JSONObject()
                .put("id", h.id.value)
                .put("name", h.name)
                .put("url", h.url)
                .put("kind", h.kind.name)
                .put("startup", h.startupCommand)
                .put("enabled", h.enabled)
        })
        prefs.edit { putString(KEY, json.toString()) }
    }

    private fun read(): List<Host> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Host(
                id = HostId(o.optString("id")),
                name = o.optString("name"),
                url = savedAddress(o.optString("url")),
                kind = HostKind.fromStored(o.optString("kind")),
                startupCommand = o.optString("startup"),
                enabled = o.optBoolean("enabled", true),
            )
        }.filter { it.url.isNotEmpty() }
    }

    private companion object { const val KEY = "hosts" }
}

/** Hosts saved before normalization (`studio:7000`) get the one spelling every connection uses; an unparsable one is kept as saved. */
internal fun savedAddress(url: String): String = normalizeAddress(url).getOrDefault(url)

// Own key, so opening a tab doesn't re-encode every setting. Create it before anything writes settings: the first read carries the old value over.
class PrefsLastTab(context: Context) : LastTabStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override var lastTab: List<String> = read()
        set(value) {
            if (value == field) return
            field = value
            prefs.edit { putString(KEY, JSONArray(value).toString()) }
        }

    private fun read(): List<String> {
        val text = prefs.getString(KEY, null)
            ?: runCatching { JSONObject(prefs.getString("settings", null)!!).getJSONArray(KEY).toString() }.getOrNull()
                ?.also { prefs.edit { putString(KEY, it) } }
            ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }
    }

    private companion object { const val KEY = "lastTab" }
}

/** Settings as one JSON object. Unknown or missing keys fall back to the defaults, so old files keep working. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val settings: StateFlow<Settings> get() = state

    override fun update(change: (Settings) -> Settings) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        prefs.edit { putString(KEY, encode(next).toString()) }
    }

    private fun read(): Settings {
        val text = prefs.getString(KEY, null) ?: return Settings()
        return runCatching { decode(JSONObject(text)) }.getOrNull() ?: Settings()
    }

    private companion object {
        const val KEY = "settings"

        fun encode(s: Settings): JSONObject = JSONObject()
            .put("appearance", s.appearance.name).put("tint", s.tint).put("matchProgramBackground", s.matchProgramBackground)
            .put("lightTheme", s.lightTheme).put("darkTheme", s.darkTheme).put("fontFamily", s.fontFamily.name).put("fontSize", s.fontSize).put("lineHeight", s.lineHeight)
            .put("improveContrast", s.improveContrast).put("forcePalette", s.forcePalette).put("cursorStyle", s.cursorStyle.name)
            .put("blink", s.blink.name).put("forceCursor", s.forceCursor).put("autocorrect", s.autocorrect)
            .put("keepKeyboardOpen", s.keepKeyboardOpen).put("useSymbols", s.useSymbols).put("keyLabels", s.keyLabels.name).put("haptics", s.haptics).put("copyAnimation", s.copyAnimation)
            .put("keyRepeat", s.keyRepeat).put("keyRepeatDelay", s.keyRepeatDelayMillis)
            .put("keyRepeatInterval", s.keyRepeatIntervalMillis).put("repeatLetters", s.repeatHeldLetters)
            .put("keyBarKeys", JSONArray(s.keyBarKeys.map { it.name }))
            .put("shortcutGroups", JSONArray(s.shortcutGroups.map { group -> JSONObject().put("id", group.id).put("name", group.name).put("shortcuts", JSONArray(group.shortcuts)) }))
            .put("smoothScrolling", s.smoothScrolling).put("preferGeneratedTitles", s.preferGeneratedTitles)
            .put("clientLabel", s.clientLabel).put("developerMode", s.developerMode).put("networkDiagnostics", s.networkDiagnostics)
            .put("showIdleProcesses", s.showIdleProcesses).put("notifications", s.notifications)
            .put("notifyProgramActivity", s.notifyProgramActivity).put("notifyTerminal", s.notifyTerminal)
            .put("stayConnected", s.stayConnected).put("collapsedSessions", JSONArray(s.collapsedSessions.toList())).put("collapsedHosts", JSONArray(s.collapsedHosts.toList()))
            .put("sidebarWidthDp", s.sidebarWidthDp)
            .put("animation", s.animation.name).put("paneStyle", s.paneStyle.name).put("sidebarTextStep", s.sidebarTextStep)
            .put("alertBlocked", s.alertBlocked.name).put("alertError", s.alertError.name).put("alertDone", s.alertDone.name)
            .put("anchor", s.anchor.name).put("copyOnSelection", s.copyOnSelection).put("appLinks", s.appLinks.name)
            .put("shortcuts", JSONObject(s.shortcuts as Map<*, *>))
            .put("shader", s.shader.name).put("customShader", s.customShader)

        fun decode(o: JSONObject): Settings {
            val d = Settings()
            fun b(k: String, def: Boolean) = if (o.has(k)) o.optBoolean(k, def) else def
            fun str(k: String, def: String) = o.optString(k, def).ifEmpty { def }
            fun <E : Enum<E>> e(k: String, def: E, values: Array<E>) = values.firstOrNull { it.name == o.optString(k) } ?: def
            return Settings(
                appearance = e("appearance", d.appearance, Settings.Appearance.entries.toTypedArray()),
                tint = b("tint", d.tint),
                matchProgramBackground = b("matchProgramBackground", d.matchProgramBackground),
                lightTheme = str("lightTheme", d.lightTheme),
                darkTheme = str("darkTheme", d.darkTheme),
                fontFamily = e("fontFamily", d.fontFamily, Settings.FontFamily.entries.toTypedArray()),
                fontSize = o.optInt("fontSize", d.fontSize).coerceIn(Settings.FontSizes),
                lineHeight = o.optInt("lineHeight", d.lineHeight).coerceIn(Settings.LineHeights),
                improveContrast = b("improveContrast", d.improveContrast),
                forcePalette = b("forcePalette", d.forcePalette),
                cursorStyle = e("cursorStyle", d.cursorStyle, Settings.CursorStyle.entries.toTypedArray()),
                blink = e("blink", d.blink, Settings.Blink.entries.toTypedArray()),
                forceCursor = b("forceCursor", d.forceCursor),
                autocorrect = b("autocorrect", d.autocorrect),
                keepKeyboardOpen = b("keepKeyboardOpen", d.keepKeyboardOpen),
                keyRepeat = b("keyRepeat", d.keyRepeat),
                keyRepeatDelayMillis = o.optInt("keyRepeatDelay", d.keyRepeatDelayMillis).coerceIn(100, 2000),
                keyRepeatIntervalMillis = o.optInt("keyRepeatInterval", d.keyRepeatIntervalMillis).coerceIn(20, 500),
                repeatHeldLetters = b("repeatLetters", d.repeatHeldLetters),
                keyBarKeys = o.optJSONArray("keyBarKeys")?.let { keys ->
                    (0 until keys.length()).mapNotNull { i ->
                        KeyBarKey.entries.find { it.name == keys.optString(i) }
                    }.distinct()
                } ?: d.keyBarKeys,
                shortcutGroups = o.optJSONArray("shortcutGroups")?.let { groups ->
                    (0 until minOf(groups.length(), 32)).mapNotNull { i ->
                        val group = groups.optJSONObject(i) ?: return@mapNotNull null
                        val id = group.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val name = group.optString("name").take(80).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val values = group.optJSONArray("shortcuts") ?: return@mapNotNull null
                        val shortcuts = (0 until minOf(values.length(), 256))
                            .map { values.optString(it).take(1024) }
                            .filter { it.isNotEmpty() }
                        ShortcutGroup(id, name, shortcuts)
                    }.distinctBy { it.id }
                } ?: d.shortcutGroups,
                useSymbols = b("useSymbols", d.useSymbols),
                keyLabels = e("keyLabels", d.keyLabels, Settings.KeyLabels.entries.toTypedArray()),
                haptics = b("haptics", d.haptics),
                copyAnimation = b("copyAnimation", d.copyAnimation),
                smoothScrolling = b("smoothScrolling", d.smoothScrolling),
                preferGeneratedTitles = b("preferGeneratedTitles", d.preferGeneratedTitles),
                clientLabel = o.optString("clientLabel", d.clientLabel),
                developerMode = b("developerMode", d.developerMode),
                networkDiagnostics = b("networkDiagnostics", d.networkDiagnostics),
                showIdleProcesses = b("showIdleProcesses", d.showIdleProcesses),
                notifications = b("notifications", d.notifications),
                notifyProgramActivity = b("notifyProgramActivity", d.notifyProgramActivity),
                notifyTerminal = b("notifyTerminal", d.notifyTerminal),
                stayConnected = b("stayConnected", d.stayConnected),
                collapsedSessions = o.optJSONArray("collapsedSessions")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: d.collapsedSessions,
                collapsedHosts = o.optJSONArray("collapsedHosts")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: d.collapsedHosts,
                sidebarWidthDp = o.optInt("sidebarWidthDp", d.sidebarWidthDp),
                animation = e("animation", d.animation, Settings.Animation.entries.toTypedArray()),
                paneStyle = e("paneStyle", d.paneStyle, Settings.PaneStyle.entries.toTypedArray()),
                sidebarTextStep = o.optInt("sidebarTextStep", d.sidebarTextStep).coerceIn(Settings.SidebarTextSteps),
                alertBlocked = e("alertBlocked", d.alertBlocked, Settings.Alert.entries.toTypedArray()),
                alertError = e("alertError", d.alertError, Settings.Alert.entries.toTypedArray()),
                alertDone = e("alertDone", d.alertDone, Settings.Alert.entries.toTypedArray()),
                anchor = e("anchor", d.anchor, Settings.Anchor.entries.toTypedArray()),
                copyOnSelection = b("copyOnSelection", d.copyOnSelection),
                appLinks = e("appLinks", d.appLinks, Settings.AppLinks.entries.toTypedArray()),
                shader = e("shader", d.shader, Settings.Shader.entries.toTypedArray()),
                customShader = o.optString("customShader", d.customShader).take(64 * 1024),
                shortcuts = o.optJSONObject("shortcuts")?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } } ?: d.shortcuts,
            )
        }
    }
}
