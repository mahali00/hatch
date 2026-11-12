package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.HatchList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.mahali.hatch.core.Settings.Appearance
import dev.mahali.hatch.core.SettingsStore

@Composable
internal fun InterfacePage(store: SettingsStore, back: (() -> Unit)?) {
    val s by store.settings.collectAsState()
    SettingsPage("Interface", back) {
        HatchList {
            section(
                "Appearance",
                footer = "Auto follows your device. Each appearance uses its own terminal theme. Tint with theme colors the " +
                    "interface with your terminal theme; turn it off to use standard system colors.",
            ) {
                choice("Color scheme", s.appearance, Appearance.entries, { it.name }) { a -> store.update { it.copy(appearance = a) } }
                switch("Tint with theme", s.tint) { on -> store.update { it.copy(tint = on) } }
            }
            section(
                "Panes",
                footer = "Match program backgrounds colors the screen around a terminal with the background a full-screen " +
                    "program, such as an editor, paints.",
            ) {
                segmented("Pane style", s.paneStyle, Settings.PaneStyle.entries, { it.name }, icon = { paneStyleIcon(it) }) { p ->
                    store.update { it.copy(paneStyle = p) }
                }
                switch("Match program backgrounds", s.matchProgramBackground) { on -> store.update { it.copy(matchProgramBackground = on) } }
            }
            section("Sidebar") {
                slider("Sidebar text size", s.sidebarTextStep, Settings.SidebarTextSteps, sidebarTextLabel(s.sidebarTextStep)) { step ->
                    store.update { it.copy(sidebarTextStep = step) }
                }
            }
            section("Animation") {
                segmented("Animation style", s.animation, Settings.Animation.entries, { it.name }) { a -> store.update { it.copy(animation = a) } }
            }
        }
    }
}

private fun paneStyleIcon(style: Settings.PaneStyle) = when (style) {
    Settings.PaneStyle.Comfortable -> HatchIcons.LayoutFit
    Settings.PaneStyle.Compact -> HatchIcons.LayoutAuto
}

private fun sidebarTextLabel(step: Int) = when {
    step == 0 -> "Standard"
    step < 0 -> "Smaller"
    else -> "Larger"
}
