package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchPillButton
import dev.mahali.hatch.design.chrome
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun DeveloperPage(settings: SettingsStore, crashes: CrashReports, back: (() -> Unit)?) {
    val s by settings.settings.collectAsState()
    SettingsPage("Developer", back) {
        HatchList {
            section("Developer") {
                switch("Developer Mode", s.developerMode, subtitle = "Expose settings for development and debugging.") { on ->
                    settings.update { it.copy(developerMode = on) }
                }
            }
            section("Crash Reports", footer = "Kept on this phone only. Share sends them as text, to wherever you choose.") {
                row("Crash Reports") {
                    HatchListRow(trailing = {
                        if (crashes.count > 0) {
                            HatchPillButton("Share", crashes.onShare)
                            HatchPillButton("Clear", crashes.onClear, color = chrome.secondary)
                        }
                    }) {
                        Text(if (crashes.count == 0) "No crashes" else "${crashes.count} saved", style = Hatch.type.body, color = chrome.text)
                        crashes.latest?.let { Text(it, style = Hatch.type.footnote, color = chrome.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            section(footer = "Floats the network path and each host's connection over the screen.", visible = s.developerMode) {
                switch("Network Diagnostics", s.networkDiagnostics) { on -> settings.update { it.copy(networkDiagnostics = on) } }
            }
        }
    }
}
