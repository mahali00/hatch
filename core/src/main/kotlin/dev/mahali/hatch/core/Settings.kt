package dev.mahali.hatch.core

public data class Settings(
    val appearance: Appearance = Appearance.Auto,
    val tint: Boolean = true,
    val matchProgramBackground: Boolean = false,
    val lightTheme: String = "merino-light",
    val darkTheme: String = "oxblood-library",
    val fontFamily: FontFamily = FontFamily.JetBrainsMono,
    val fontSize: Int = 13,
    val lineHeight: Int = 0,
    val improveContrast: Boolean = true,
    val forcePalette: Boolean = false,
    val cursorStyle: CursorStyle = CursorStyle.Block,
    val blink: Blink = Blink.Normal,
    val forceCursor: Boolean = false,
    val autocorrect: Boolean = false,
    val keepKeyboardOpen: Boolean = false,
    val keyRepeat: Boolean = true,
    // Mac parity: about 30 keys a second after 0.3 s, letters too.
    val keyRepeatDelayMillis: Int = 300,
    val keyRepeatIntervalMillis: Int = 33,
    val repeatHeldLetters: Boolean = true,
    val keyBarKeys: List<KeyBarKey> = KeyBarKey.entries,
    val shortcutGroups: List<ShortcutGroup> = emptyList(),
    val useSymbols: Boolean = true,
    val keyLabels: KeyLabels = KeyLabels.Mac,
    val haptics: Boolean = true,
    val copyAnimation: Boolean = true,
    val smoothScrolling: Boolean = true,
    val preferGeneratedTitles: Boolean = true,
    val clientLabel: String = "",
    val developerMode: Boolean = false,
    val networkDiagnostics: Boolean = false,
    val showIdleProcesses: Boolean = false,
    val notifications: Boolean = false,
    val notifyProgramActivity: Boolean = true,
    val notifyTerminal: Boolean = true,
    val stayConnected: Boolean = true,
    val collapsedSessions: Set<String> = emptySet(),
    val collapsedHosts: Set<String> = emptySet(),
    val sidebarWidthDp: Int = 280,
    val animation: Animation = Animation.Snappy,
    val paneStyle: PaneStyle = PaneStyle.Comfortable,
    val sidebarTextStep: Int = 0,
    val alertBlocked: Alert = Alert.Sound,
    val alertError: Alert = Alert.Sound,
    val alertDone: Alert = Alert.Sound,
    val anchor: Anchor = Anchor.Top,
    val copyOnSelection: Boolean = false,
    val appLinks: AppLinks = AppLinks.AskFirst,
    val shortcuts: Map<String, String> = emptyMap(),
    val shader: Shader = Shader.None,
    val customShader: String = "",
) {
    public enum class Appearance { Auto, Light, Dark }
    public enum class FontFamily(public val label: String) {
        SystemMonospace("System Monospace"), JetBrainsMono("JetBrains Mono"), GeistMono("Geist Mono"), RobotoMono("Roboto Mono")
    }
    public enum class CursorStyle { Block, Bar, Underline }

    public enum class KeyLabels(public val label: String) { Mac("Mac"), Pc("Windows & Linux") }
    public enum class Blink { Off, Slow, Normal, Fast }
    public enum class Animation { Snappy, Natural, Off }
    public enum class PaneStyle { Comfortable, Compact }

    public enum class Anchor { Top, Bottom }

    /** Each asset is a renderer file at `shaders/[asset].glsl`. */
    public enum class Shader(public val label: String, public val asset: String? = null) {
        None("None"),
        Warp("Warp", "warp"), Sweep("Sweep", "sweep"), Comet("Comet", "comet"),
        Blaze("Blaze", "blaze"), BlazeTapered("Blaze Tapered", "blaze-tapered"), BlazeNoTrail("Blaze, No Trail", "blaze-no-trail"),
        Rainbow("Rainbow", "rainbow"), Gradient("Gradient", "gradient"), Frozen("Frozen", "frozen"),
        Crt("CRT"), Bloom("Bloom"), CursorGlow("Cursor Glow"), Custom("Custom"),
    }

    public enum class AppLinks(public val label: String) { AskFirst("Ask First"), WebOnly("Web Only") }

    public enum class Alert(public val label: String) { Sound("Notify with sound"), Silent("Notify silently"), Off("Don't notify") }

    public fun alertFor(state: ProgramState): Alert? = when (state) {
        ProgramState.Blocked -> alertBlocked
        ProgramState.Error -> alertError
        ProgramState.Done -> alertDone
        else -> null
    }

    public companion object {
        public val FontSizes: IntRange = 9..24
        public val LineHeights: IntRange = 0..60
        public const val LINE_HEIGHT_STEP: Int = 5
        public val SidebarTextSteps: IntRange = -2..2
    }
}
