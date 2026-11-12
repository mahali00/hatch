package dev.mahali.hatch.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import dev.mahali.hatch.design.HatchListCard
import dev.mahali.hatch.design.HatchListDivider
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.chrome

internal data class SettingEntry(val title: String, val page: Page, val pageTitle: String)

/** Keep in step with the pages' row titles. */
internal val SettingsIndex: List<SettingEntry> = buildList {
    fun on(page: Page, pageTitle: String, vararg titles: String) = titles.forEach { add(SettingEntry(it, page, pageTitle)) }
    on(Page.Interface, "Interface", "Appearance", "Color scheme", "Tint with theme", "Match program backgrounds")
    on(Page.Themes, "Themes", "Light Mode", "Dark Mode", "Themes")
    on(
        Page.Terminal, "Terminal", "Terminal font", "Font size", "Line height", "Improve text contrast", "Cursor style", "Blink speed",
        "Force style", "Force theme palette for text", "Smooth scrolling", "Prefer generated titles",
        "Copy on selection", "Links that open apps", "Terminal anchoring", "Shader", "Effects", "CRT", "Bloom", "Cursor Glow",
    )
    on(
        Page.Keyboard, "Keyboard", "Autocorrect", "Keep keyboard open", "Key Bar", "Key labels", "Use symbols", "Haptic feedback",
        "Animation on copy", "Hardware Keyboard", "Key repeat", "Delay until repeat", "Key repeat interval", "Repeat held letters", "Command palette",
    )
    on(Page.KeyBarLayout, "Keyboard", "Layout", "Arrange keys", "Hide keys")
    on(Page.ShortcutGroups, "Keyboard", "Shortcut Groups")
    on(Page.Hosts, "Remote Hosts", "Hosts", "Add Host", "Remote Hosts", "Client Info", "Label")
    on(Page.Notifications, "Notifications", "Notifications", "Stay connected in background", "Needs input", "Failed", "Finished", "Program Activity")
    on(Page.Interface, "Interface", "Pane style", "Sidebar text size", "Animation style")
    on(Page.Developer, "Developer", "Developer Mode", "Network Diagnostics")
    on(Page.About, "About", "Version", "Device", "Terminal engine", "Copy Version Info", "Copy Debug Info", "Acknowledgements")
}

internal fun searchSettings(query: String): List<SettingEntry> = query.trim().let { q ->
    SettingsIndex.filter { it.title.contains(q, ignoreCase = true) || it.pageTitle.contains(q, ignoreCase = true) }
}

@Composable
internal fun SearchResults(query: String, open: (Page) -> Unit) {
    val results = searchSettings(query)
    HatchListCard {
        if (results.isEmpty()) HatchListRow("No Results", titleColor = chrome.secondary)
        results.forEachIndexed { i, entry ->
            if (i > 0) HatchListDivider()
            HatchListRow(entry.title, subtitle = entry.pageTitle, onClick = { open(entry.page) })
        }
    }
}

