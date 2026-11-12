package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.HatchPillButton
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchList
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchTopBar
import dev.mahali.hatch.design.SelectedMark
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.Footnote
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.RowChevron
import dev.mahali.hatch.design.SectionHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.design.TerminalTheme
import dev.mahali.hatch.design.Themes
import dev.mahali.hatch.design.chrome

@Composable
internal fun ThemesPage(store: SettingsStore, back: (() -> Unit)?, open: (Page) -> Unit) {
    val s by store.settings.collectAsState()
    val context = LocalContext.current
    SettingsPage("Themes", back) {
        SectionHeader("Light Mode", top = 0.dp)
        ThemeCard(Themes.find(context, s.lightTheme, dark = false), files = true, onClick = { open(Page.LightThemes) }) { Chevron(it) }
        SectionHeader("Dark Mode")
        ThemeCard(Themes.find(context, s.darkTheme, dark = true), files = true, onClick = { open(Page.DarkThemes) }) { Chevron(it) }
        Footnote(
            "Themes are matched to an appearance by their background color. The interface follows the theme " +
                "when Tint with theme is on.",
        )
    }
}

@Composable
internal fun ThemePickerPage(store: SettingsStore, dark: Boolean, back: (() -> Unit)?) {
    val s by store.settings.collectAsState()
    val context = LocalContext.current
    val themes = remember(dark) { Themes.all(context).filter { it.dark == dark }.sortedBy { it.name } }
    val current = if (dark) s.darkTheme else s.lightTheme
    val choose = { theme: TerminalTheme ->
        store.update { if (dark) it.copy(darkTheme = theme.id) else it.copy(lightTheme = theme.id) }
    }
    LazyColumn(
            Modifier.fillMaxSize().background(chrome.background).windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Hatch.space.m),
        ) {
            item {
                    HatchTopBar(if (dark) "Dark Mode" else "Light Mode", back) {
                    Text("${themes.size} themes", style = HatchType.Callout, color = chrome.secondary)
                }
            }
            items(themes, key = { it.id }) { theme ->
                val selected = theme.id == current
                ThemeCard(theme, files = false, selected = selected, onClick = { choose(theme) }) {
                    if (selected) SelectedMark()
                }
            }
    }
}

@Composable
private fun ThemeCard(
    theme: TerminalTheme,
    files: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
    trailing: @Composable (Color) -> Unit,
) {
    val fg = Color(theme.foreground)
    val bg = Color(theme.background)
    val shape = HatchShapes.Card
    val ring = if (selected) Modifier.border(2.dp, chrome.accent, shape) else Modifier.border(1.dp, fg.copy(alpha = 0.14f), shape)
    val sample = HatchType.Footnote.copy(fontFamily = Settings.FontFamily.JetBrainsMono.compose())
    Row(
        Modifier.padding(horizontal = Dimens.ScreenInset).fillMaxWidth().clip(shape).then(ring).background(bg)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .padding(start = Dimens.CardPadding, end = Dimens.CardPadding, top = 13.dp, bottom = Dimens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(theme.name, style = HatchType.Title, color = fg)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(theme.ansi[2]))) { append("$ ") }
                    append("ls -la")
                },
                Modifier.padding(top = 6.dp), style = sample, color = fg,
            )
            if (files) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(theme.ansi[4]))) { append("file.txt ") }
                        withStyle(SpanStyle(color = Color(theme.ansi[6]))) { append("README.md") }
                    },
                    style = sample,
                )
            }
            Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy((-1).dp)) {
                for (color in theme.ansi.take(8)) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(bg).padding(1.5.dp).clip(CircleShape).background(Color(color)))
                }
            }
        }
        trailing(fg)
    }
}

@Composable
private fun Chevron(tint: Color) = RowChevron(tint.copy(alpha = 0.6f))

