package dev.mahali.hatch

import androidx.compose.runtime.DisposableEffect
import dev.mahali.hatch.core.combo
import dev.mahali.hatch.core.AppShortcut
import dev.mahali.hatch.design.adjusted
import dev.mahali.hatch.design.AnimationStyle
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchScale
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.design.Chrome
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchTheme
import dev.mahali.hatch.design.HatchPill
import dev.mahali.hatch.design.Themes
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.feature.pairing.AddHostSheet
import dev.mahali.hatch.feature.pairing.FirstLaunchScreen
import dev.mahali.hatch.feature.sessions.SessionsScreen
import dev.mahali.hatch.feature.settings.AboutInfo
import dev.mahali.hatch.feature.settings.EditHostSheet
import dev.mahali.hatch.feature.settings.SettingsScreen
import dev.mahali.hatch.feature.terminal.TerminalScreen
import dev.mahali.hatch.feature.terminal.terminalControlsHeight
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val WideWidth = 600.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppRoot(app: HatchApp, opened: WindowRef?, onOpenedConsumed: () -> Unit, openedBlock: BlockId? = null) {
    val settings by app.settings.settings.collectAsState()
    // Server state is collected where shown: at the root, every server event recomposed the whole app.
    val hosts by app.hosts.hosts.collectAsState()
    val context = LocalContext.current
    val dark = when (settings.appearance) {
        Settings.Appearance.Auto -> isSystemInDarkTheme()
        Settings.Appearance.Light -> false
        Settings.Appearance.Dark -> true
    }
    val theme = remember(dark, settings.lightTheme, settings.darkTheme) {
        Themes.find(context, if (dark) settings.darkTheme else settings.lightTheme, dark)
    }

    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        activity?.enableEdgeToEdge(bars, bars)
    }

    val nav = rememberSaveable(saver = AppNavigator.saver(app.settings, app.lastTab, app.rex.state)) { AppNavigator(app.settings, app.lastTab, app.rex.state) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(nav.screen) { if (nav.screen == Screen.Settings) keyboard?.hide() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val foreground = rememberForeground()

    LaunchedEffect(nav) { nav.follow(context, app.hosts) }
    LaunchedEffect(opened, openedBlock) {
        val ref = opened ?: return@LaunchedEffect
        nav.openNotified(app.rex, ref, openedBlock)?.let { snackbar.showSnackbar(it) }
        onOpenedConsumed()
    }
    LaunchedEffect(Unit) { app.actions.failures.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { app.actions.opens.collect { nav.open(it) } }
    LaunchedEffect(app.rex) { app.rex.inputFailures.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(nav.target, nav.screen, foreground) {
        app.notices.visible = if (nav.screen == Screen.Terminal && foreground) nav.target else null
    }

    // The unfolded screen sits beside the Mac client and takes its sizes; the folded phone keeps its own.
    val wideScreen = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= WideWidth
    val scale = remember(wideScreen, settings.animation, settings.paneStyle, settings.sidebarTextStep) {
        (if (wideScreen) HatchScale.Desktop else HatchScale.Phone).adjusted(
            AnimationStyle.valueOf(settings.animation.name),
            compactPanes = settings.paneStyle == Settings.PaneStyle.Compact,
            sidebarTextStep = settings.sidebarTextStep,
        )
    }
    // Match program backgrounds: the chrome around a shown terminal takes its focused pane's background, as on the Mac.
    var programBackground by remember { mutableStateOf<Int?>(null) }
    val terminalShown = nav.target != null && rootView(hosts.isNotEmpty(), nav.screen, wideScreen, hasTab = true).let { it == RootView.Wide || it == RootView.Terminal }
    val programTone = programBackground.takeIf { settings.matchProgramBackground && terminalShown }
    val chromeColors = remember(theme, settings.tint, programTone) {
        Chrome.of(theme, settings.tint).let { c -> programTone?.let { c.copy(terminal = androidx.compose.ui.graphics.Color(it or 0xFF000000.toInt())) } ?: c }
    }
    val onProgramBackground: (Int) -> Unit = remember { { programBackground = it } }
    HatchTheme(chromeColors, scale) {
        val combos = remember(settings.shortcuts) { AppShortcut.entries.mapNotNull { s -> settings.combo(s)?.let { it to s } }.toMap() }
        DisposableEffect(combos, nav, hosts) {
            Shortcuts.handler = { combo -> combos[combo]?.let { runShortcut(it, app, nav, hosts); true } ?: false }
            onDispose { Shortcuts.handler = null }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(chrome.background)) {
            val wide = maxWidth >= WideWidth
            LaunchedEffect(wide) { nav.setWide(wide) }
            val current = nav.target
            val screen = nav.screen
            val tabStates = rememberSaveableStateHolder()
            when (rootView(hasHosts = hosts.isNotEmpty(), screen, wide, hasTab = current != null)) {
                RootView.Settings -> {
                    BackHandler { nav.settingsBack() }
                    SettingsPage(app, nav, wide)
                }
                RootView.Welcome -> FirstLaunchScreen(onAddHost = { nav.addingHost = true }, onOpenSettings = { nav.openSettings() })
                RootView.Wide -> WideLayout(app, settings, theme, nav, tabStates, onProgramBackground)
                RootView.Terminal -> current?.let { tab ->
                    BackHandler { nav.showList() }
                    tabStates.SaveableStateProvider(tab.toString()) {
                        TerminalScreen(app.rex, settings, app.actions, theme, tab, wide = false,
                            onBack = { nav.leaveTerminal(tab) }, onOpenTab = { nav.open(it) }, onCommands = { nav.commandsShown = true },
                            onTabGone = nav::tabGone, onProgramBackground = onProgramBackground)
                    }
                }
                RootView.Sessions -> SessionsScreen(
                    app.rex.state, app.settings, app.actions, onOpenTab = { nav.open(it) }, onOpenSettings = { nav.openSettings() },
                    onEditHost = { nav.editingHost = it }, onOpenPane = { ref, block -> nav.openPane(app.rex, ref, block) },
                    onCommands = { nav.commandsShown = true },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) { HatchPill(it.visuals.message) }
            if (settings.developerMode && settings.networkDiagnostics) {
                val terminalVisible = current != null && screen != Screen.Settings && (wide || screen == Screen.Terminal)
                val header = Dimens.SidebarToggleTop + Dimens.TouchTarget + 6.dp
                NetworkDiagnostics(app.rex, app.networkPath, Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Hatch.space.s)
                    .padding(top = header + 6.dp,
                        bottom = if (terminalVisible) terminalControlsHeight(WindowInsets.isImeVisible) else 0.dp))
            }
        }

        if (nav.addingHost) AddHostSheet(app.probe, hosts, onAdded = { host ->
            app.hosts.save(host)
            nav.addingHost = false
            scope.launch { snackbar.showSnackbar("${host.name} added") }
        }, onDismiss = { nav.addingHost = false })

        if (nav.commandsShown) {
            WorkspaceCommands(app.rex.state, settings, current = nav.target, app.settings, app.actions,
                onOpenTab = { nav.open(it) }, onSettings = { nav.openSettings() }, onReconnect = { app.rex.reconnect(it) },
                onDismiss = { nav.commandsShown = false })
        }

        nav.editingHost?.let { id -> hosts.firstOrNull { it.id == id }?.let { EditHost(app, it) { nav.editingHost = null } } }
    }
}

/** Settings, collecting the server state itself so its changes recompose this page, not the app. */
@Composable
private fun SettingsPage(app: HatchApp, nav: AppNavigator, wide: Boolean) {
    val state by app.rex.state.collectAsState()
    SettingsScreen(
        settings = app.settings, hosts = app.hosts, state = state, actions = app.actions,
        about = AboutInfo(BuildConfig.VERSION_NAME, android.os.Build.MODEL, "libghostty-vt", crashReports(app)),
        onAddHost = { nav.addingHost = true }, onEditHost = { nav.editingHost = it },
        onClose = { nav.closeSettings() }, wide = wide,
    )
}

@Composable
private fun EditHost(app: HatchApp, host: Host, onDone: () -> Unit) {
    val id = host.id
    val hostState by remember(id) { app.rex.state.map { it.byHost[id] }.distinctUntilChanged() }
        .collectAsState(remember(id) { app.rex.state.value.byHost[id] })
    EditHostSheet(
        host, hostState, onSave = { app.hosts.save(it); onDone() },
        onRemove = { app.hosts.remove(id); onDone() }, onRetry = { app.rex.reconnect(id) },
        onDismiss = onDone,
    )
}

@Composable
private fun rememberForeground(): Boolean {
    var started by remember { mutableStateOf(true) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }
    return started
}

@Composable
private fun crashReports(app: HatchApp): dev.mahali.hatch.feature.settings.CrashReports {
    val context = androidx.compose.ui.platform.LocalContext.current
    var version by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val files = androidx.compose.runtime.remember(version) { app.crashLog.reports() }
    val latest = androidx.compose.runtime.remember(files) { files.firstOrNull()?.useLines { it.take(4).filter(String::isNotBlank).drop(1).joinToString(" · ") } }
    return dev.mahali.hatch.feature.settings.CrashReports(
        count = files.size, latest = latest,
        onShare = { app.crashLog.shareIntent()?.let(context::startActivity) },
        onClear = { app.crashLog.clear(); version++ },
    )
}

private fun runShortcut(shortcut: AppShortcut, app: HatchApp, nav: AppNavigator, hosts: List<Host>) {
    val state = app.rex.state.value
    val target = nav.target
    fun step(list: List<String>, current: String?, forward: Boolean): String? {
        if (list.isEmpty()) return null
        val i = list.indexOf(current)
        return list[if (i < 0) 0 else (i + (if (forward) 1 else -1)).mod(list.size)]
    }
    when (shortcut) {
        AppShortcut.CommandPalette -> nav.commandsShown = true
        AppShortcut.Settings -> nav.openSettings()
        AppShortcut.ToggleSidebar -> nav.showSidebar(!nav.sidebarShown)
        AppShortcut.NewSession -> (target?.host ?: hosts.firstOrNull()?.id)?.let(app.actions::newSession)
        AppShortcut.NewTab -> target?.let { app.actions.newTab(it.host, it.session) }
        AppShortcut.CloseTab -> target?.let(app.actions::closeTab)
        AppShortcut.NextTab, AppShortcut.PreviousTab -> target?.let { t ->
            val windows = state.byHost[t.host]?.sessionsById?.get(t.session)?.windows ?: return
            step(windows.map { it.value }, t.window.value, shortcut == AppShortcut.NextTab)
                ?.let { w -> nav.open(WindowRef(t.host, t.session, windows.first { it.value == w })) }
        }
        AppShortcut.NextSession, AppShortcut.PreviousSession -> target?.let { t ->
            val host = state.byHost[t.host] ?: return
            val next = step(host.sessions.map { it.value }, t.session.value, shortcut == AppShortcut.NextSession) ?: return
            val session = host.sessions.first { it.value == next }
            host.sessionsById[session]?.windows?.firstOrNull()?.let { nav.open(WindowRef(t.host, session, it)) }
        }
    }
}
