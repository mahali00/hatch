package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.HatchShortcutChip
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.core.combo
import dev.mahali.hatch.core.AppShortcut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.design.HatchList

@Composable
internal fun KeyboardPage(store: SettingsStore, back: (() -> Unit)?, open: (Page) -> Unit) {
    val s by store.settings.collectAsState()
    fun set(change: (Settings) -> Settings) = store.update(change)
    SettingsPage("Keyboard", back) {
        HatchList {
            section("On-Screen Keyboard") {
                switch("Autocorrect", s.autocorrect, "Let the keyboard correct and predict words.") { on -> set { it.copy(autocorrect = on) } }
                switch("Keep keyboard open", s.keepKeyboardOpen, "Open the keyboard again when you come back to a terminal.") { on ->
                    set { it.copy(keepKeyboardOpen = on) }
                }
            }
            section(
                "Hardware Keyboard",
                footer = "These timings apply to hardware key events received by the terminal. Text composition and input-method shortcuts stay with your keyboard.",
            ) {
                switch("Key repeat", s.keyRepeat) { on -> set { it.copy(keyRepeat = on) } }
                stepper(
                    "Delay until repeat", seconds(s.keyRepeatDelayMillis), s.keyRepeatDelayMillis > 100, s.keyRepeatDelayMillis < 2000,
                    { set { it.copy(keyRepeatDelayMillis = (it.keyRepeatDelayMillis - 50).coerceAtLeast(100)) } },
                    { set { it.copy(keyRepeatDelayMillis = (it.keyRepeatDelayMillis + 50).coerceAtMost(2000)) } },
                )
                stepper(
                    "Key repeat interval", seconds(s.keyRepeatIntervalMillis), s.keyRepeatIntervalMillis > 20, s.keyRepeatIntervalMillis < 500,
                    { set { it.copy(keyRepeatIntervalMillis = (it.keyRepeatIntervalMillis - 10).coerceAtLeast(20)) } },
                    { set { it.copy(keyRepeatIntervalMillis = (it.keyRepeatIntervalMillis + 10).coerceAtMost(500)) } },
                )
                switch("Repeat held letters", s.repeatHeldLetters, "Repeat letters sent directly by a hardware keyboard.") { on -> set { it.copy(repeatHeldLetters = on) } }
            }
            section(
                "Key Bar",
                footer = "Tap Commands in your workspace to find commands, switch sessions, or change theme. Control, Option, and Shift apply to one key; " +
                    "tap again to cancel. Swipe the key bar for more keys.",
            ) {
                link("Layout", subtitle = "Arrange and hide the built-in keys") { open(Page.KeyBarLayout) }
                link("Shortcut Groups", subtitle = s.shortcutGroups.joinToString { it.name }.ifBlank { "None" }) { open(Page.ShortcutGroups) }
                choice("Key labels", s.keyLabels, Settings.KeyLabels.entries, { it.label }) { k -> set { it.copy(keyLabels = k) } }
                switch("Use symbols", s.useSymbols, "Show ⌃ ⌥ ⇧ ⇥ instead of key names.", visible = s.keyLabels == Settings.KeyLabels.Mac) { on ->
                    set { it.copy(useSymbols = on) }
                }
                switch("Haptic feedback", s.haptics, "Feel a light tap when pressing key bar buttons.") { on -> set { it.copy(haptics = on) } }
                switch("Animation on copy", s.copyAnimation, "Flash the text you copy.") { on -> set { it.copy(copyAnimation = on) } }
            }
            for ((group, shortcuts) in AppShortcut.entries.groupBy { it.group }) {
                section(if (group == "App") "Shortcuts · App" else group) {
                    for (shortcut in shortcuts) {
                        row(shortcut.title) {
                            HatchListRow(shortcut.title, trailing = {
                                HatchShortcutChip(
                                    s.combo(shortcut),
                                    onRecord = { c -> set { it.copy(shortcuts = it.shortcuts + (shortcut.name to c.encode())) } },
                                    onClear = { set { it.copy(shortcuts = it.shortcuts + (shortcut.name to "")) } },
                                )
                            })
                        }
                    }
                }
            }
            section(footer = "Shortcuts work with a hardware keyboard: ⌘ is the keyboard's Search, Windows or Meta key.") {
                action("Restore Default Shortcuts") { set { it.copy(shortcuts = emptyMap()) } }
            }
        }
    }
}
