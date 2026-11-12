package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.SettingsIcon
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchSearchField
import dev.mahali.hatch.design.CardSpacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import android.content.Context
import android.os.Build
import android.provider.Settings.Global
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.mahali.hatch.design.ListMetrics
import dev.mahali.hatch.design.LocalListMetrics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.IconBadge
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchListCard
import dev.mahali.hatch.design.HatchListDivider
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.RowChevron
import dev.mahali.hatch.design.Themes
import dev.mahali.hatch.design.chrome
import kotlinx.coroutines.delay

@Composable
internal fun RootPage(env: PageEnv, selected: Page?, open: (Page) -> Unit) {
    val s by env.settings.settings.collectAsState()
    val context = LocalContext.current
    val dark = when (s.appearance) {
        Settings.Appearance.Light -> false
        Settings.Appearance.Dark -> true
        Settings.Appearance.Auto -> isSystemInDarkTheme()
    }
    val theme = remember(s.lightTheme, s.darkTheme, dark) { Themes.find(context, if (dark) s.darkTheme else s.lightTheme, dark).name }
    var query by rememberSaveable { mutableStateOf("") }

    val sidebar = selected != null
    @Composable
    fun Group(content: @Composable ColumnScope.() -> Unit) =
        if (sidebar) {
            // The Mac's 32 pt sidebar rows, not the 44 of a settings list.
            CompositionLocalProvider(LocalListMetrics provides ListMetrics.Sidebar.copy(restingText = 1f)) {
                Column(Modifier.padding(horizontal = SidebarPadding), content = content)
            }
        } else HatchListCard(content = content)
    @Composable
    fun Separator() { if (!sidebar) HatchListDivider() }
    @Composable
    fun GroupBreak() = if (sidebar) Spacer(Modifier.height(GroupGap)) else CardSpacer()

    @Composable
    fun Entry(page: Page, title: String, value: String?, icon: SettingsIcon) {
        HatchListRow(
            onClick = { open(page) }, selected = page == selected, leading = { IconBadge(icon) },
            trailing = { if (!sidebar) RowChevron() },
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val valueMaxWidth = maxWidth * 0.5f
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.s)) {
                    Text(title, Modifier.weight(1f), style = HatchType.Body, color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (value != null && !sidebar) Text(value, Modifier.widthIn(max = valueMaxWidth), style = HatchType.Body,
                        color = chrome.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SettingsPage("Settings", onBack = env.onClose, backIcon = HatchIcons.Close, backDescription = "Close") {
            if (query.isNotBlank()) {
                SearchResults(query, open)
            } else {
                Group {
                    Entry(Page.Hosts, "Remote Hosts", hostsStatus(env), SettingsIcon.Hosts)
                }
                GroupBreak()
                Group {
                    Entry(Page.Interface, "Interface", s.appearance.name, SettingsIcon.Interface)
                    Separator()
                    Entry(Page.Themes, "Themes", theme, SettingsIcon.Themes)
                }
                GroupBreak()
                Group {
                    Entry(Page.Terminal, "Terminal", null, SettingsIcon.Terminal)
                    Separator()
                    Entry(Page.Keyboard, "Keyboard", null, SettingsIcon.Keyboard)
                    Separator()
                    Entry(Page.Notifications, "Notifications", if (s.notifications) "On" else "Off", SettingsIcon.Notifications)
                }
                GroupBreak()
                Group {
                    Entry(Page.Developer, "Developer", null, SettingsIcon.Developer)
                    Separator()
                    Entry(Page.About, "About", env.about.version, SettingsIcon.About)
                }
            }
            Spacer(Modifier.height(72.dp))
        }
        HatchSearchField(
            query, { query = it }, "Search Settings",
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(Hatch.space.l),
        )
    }
}

/** The store takes every keystroke; the server hears only the settled value. */
@Composable
internal fun LabelRow(env: PageEnv, saved: String) {
    val context = LocalContext.current
    var label by rememberSaveable { mutableStateOf(saved) }
    var edited by remember { mutableStateOf(false) }
    // Settings load from disk after the first frame; follow them until the user types.
    LaunchedEffect(saved) { if (!edited) label = saved }
    LaunchedEffect(label) {
        if (!edited) return@LaunchedEffect
        delay(600)
        env.actions.setClientLabel(label.trim())
    }
    val model = remember { deviceModel(context) }
    HatchListRow(
        "Label",
        trailing = {
            val style = HatchType.Body.copy(color = chrome.secondary, textAlign = TextAlign.End)
            BasicTextField(
                label,
                onValueChange = {
                    label = it
                    edited = true
                    env.settings.update { s -> s.copy(clientLabel = it) }
                },
                modifier = Modifier.widthIn(min = 80.dp, max = 200.dp).semantics { contentDescription = "Label" },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(chrome.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    // The model is the placeholder: it is what the server shows when no label is set.
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (label.isEmpty()) Text(model, style = style.copy(color = chrome.secondary.copy(alpha = 0.6f)))
                        inner()
                    }
                },
            )
        },
    )
}

internal fun deviceModel(context: Context): String =
    Global.getString(context.contentResolver, Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

// The Mac's 32 pt sidebar rows open to 45 pt between groups.
private val SidebarPadding = 8.dp
private val GroupGap = 12.dp

private fun hostsStatus(env: PageEnv): String? {
    if (env.savedHosts.isEmpty()) return null
    val online = env.savedHosts.count { env.state.byHost[it.id]?.link == Link.Online }
    return "$online connected"
}
