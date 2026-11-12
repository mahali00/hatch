package dev.mahali.hatch.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostStore
import dev.mahali.hatch.core.RexActions
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.LocalChrome
import dev.mahali.hatch.design.chrome

public data class AboutInfo(val version: String, val device: String, val engine: String, val crashes: CrashReports = CrashReports())

public data class CrashReports(val count: Int = 0, val latest: String? = null, val onShare: () -> Unit = {}, val onClear: () -> Unit = {})

@Composable
public fun SettingsScreen(
    settings: SettingsStore,
    hosts: HostStore,
    state: AppState,
    actions: RexActions,
    about: AboutInfo,
    onAddHost: () -> Unit,
    onEditHost: (HostId) -> Unit,
    onClose: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    // Saved, so rotating or unfolding keeps the place.
    var stack by rememberSaveable { mutableStateOf(listOf(Page.Root)) }
    val savedHosts by hosts.hosts.collectAsState()
    val env = PageEnv(settings, hosts, savedHosts, state, actions, about, onAddHost, onEditHost, onClose)

    // On wide windows the root is always on screen, so its first page is the bottom of the detail stack.
    val bottom = if (wide) 2 else 1
    BackHandler { if (stack.size > bottom) stack = stack.dropLast(1) else onClose() }
    val back = { stack = stack.dropLast(1) }
    val open = { page: Page -> stack = stack + page }

    if (!wide) {
        Pages(stack.last(), env, open, back.takeIf { stack.size > 1 }, modifier)
        return
    }
    val detail = stack.drop(1).ifEmpty { listOf(Page.Hosts) }
    Row(modifier.fillMaxSize().background(chrome.background)) {
        Box(Modifier.width(Hatch.layout.settingsSidebar).fillMaxHeight()) {
            RootPage(env, selected = detail.first(), open = { stack = listOf(Page.Root, it) })
        }
        CompositionLocalProvider(LocalChrome provides chrome.copy(background = chrome.workspace)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Pages(detail.last(), env, open, back.takeIf { detail.size > 1 }, Modifier)
            }
        }
    }
}

internal class PageEnv(
    val settings: SettingsStore,
    val hosts: HostStore,
    val savedHosts: List<Host>,
    val state: AppState,
    val actions: RexActions,
    val about: AboutInfo,
    val onAddHost: () -> Unit,
    val onEditHost: (HostId) -> Unit,
    val onClose: () -> Unit,
)

internal enum class Page { Root, Interface, Themes, LightThemes, DarkThemes, Terminal, Keyboard, Font, KeyBarLayout, ShortcutGroups, Hosts, Notifications, Developer, About, Acknowledgements }

@Composable
private fun Pages(page: Page, env: PageEnv, open: (Page) -> Unit, back: (() -> Unit)?, modifier: Modifier) {
    AnimatedContent(page, modifier.fillMaxSize(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") {
        when (it) {
            Page.Root -> RootPage(env, selected = null, open = open)
            Page.Interface -> InterfacePage(env.settings, back)
            Page.Themes -> ThemesPage(env.settings, back, open)
            Page.LightThemes -> ThemePickerPage(env.settings, dark = false, back = back)
            Page.DarkThemes -> ThemePickerPage(env.settings, dark = true, back = back)
            Page.Terminal -> TerminalPage(env.settings, back, open)
            Page.Keyboard -> KeyboardPage(env.settings, back, open)
            Page.Font -> FontPage(env.settings, back)
            Page.KeyBarLayout -> KeyBarLayoutPage(env.settings, back)
            Page.ShortcutGroups -> ShortcutGroupsPage(env.settings, back)
            Page.Hosts -> HostsPage(env, back)
            Page.Notifications -> NotificationsPage(env.settings, back)
            Page.Developer -> DeveloperPage(env.settings, env.about.crashes, back)
            Page.About -> AboutPage(env, back, open)
            Page.Acknowledgements -> AcknowledgementsPage(back)
        }
    }
}
