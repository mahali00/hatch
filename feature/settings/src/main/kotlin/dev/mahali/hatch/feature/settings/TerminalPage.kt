package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.Revealed
import dev.mahali.hatch.design.HatchPillButton
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import dev.mahali.hatch.design.HatchList
import dev.mahali.hatch.design.CardSpacer
import dev.mahali.hatch.design.HatchStepperRow
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchStepperField
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.Settings.Blink
import dev.mahali.hatch.core.Settings.CursorStyle
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.Footnote
import dev.mahali.hatch.design.HatchListCard
import dev.mahali.hatch.design.HatchListDivider
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.chrome

@Composable
internal fun TerminalPage(store: SettingsStore, back: (() -> Unit)?, open: (Page) -> Unit) {
    val s by store.settings.collectAsState()
    fun set(change: (Settings) -> Settings) = store.update(change)
    val context = LocalContext.current
    val importShader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        if (!text.isNullOrBlank() && "mainImage" in text) set { it.copy(customShader = text.take(64 * 1024), shader = Settings.Shader.Custom) }
    }
    SettingsPage("Terminal", back) {
        HatchList {
            section("Typography", footer = "Applies to every terminal on this device, whatever your system text size.") {
                row("Terminal font") {
                    HatchListRow("Terminal font", trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.m)) {
                            Text(s.fontFamily.label, style = Hatch.type.body.copy(fontFamily = s.fontFamily.compose()), color = chrome.text)
                            HatchPillButton("Choose…", { open(Page.Font) }, color = chrome.text)
                        }
                    })
                }
                row("Font size") { FontMetrics(s, ::set) }
            }
            section(
                "Colors",
                footer = "ANSI text uses your theme. Palette mapping also maps RGB and extended text colors before correcting contrast. " +
                    "Terminal graphics keep their original colors.",
            ) {
                switch("Improve text contrast", s.improveContrast, "Adjust unreadable text against its background.") { on -> set { it.copy(improveContrast = on) } }
                switch("Force theme palette for text", s.forcePalette, "Map text colors to the nearest theme color.") { on -> set { it.copy(forcePalette = on) } }
            }
            section("Cursor") {
                choice("Cursor style", s.cursorStyle, CursorStyle.entries, { it.name }, icon = { c, selected -> CursorGlyph(c, selected) }) { c ->
                    set { it.copy(cursorStyle = c) }
                }
                choice("Blink speed", s.blink, Blink.entries, { it.name }) { b -> set { it.copy(blink = b) } }
                switch("Force style", s.forceCursor, "Ignore programs that ask for another shape.") { on -> set { it.copy(forceCursor = on) } }
            }
            section("Selection") {
                switch("Copy on selection", s.copyOnSelection, "Copy text as soon as you select it.") { on -> set { it.copy(copyOnSelection = on) } }
            }
            section(
                "Links",
                footer = "Web links open directly. Other links, such as ssh://, open the app registered for them; " +
                    "Ask First offers that in the link's menu, Web Only just copies them.",
            ) {
                choice("Links that open apps", s.appLinks, Settings.AppLinks.entries, { it.label }) { l -> set { it.copy(appLinks = l) } }
            }
            section(
                "Effects",
                footer = "Warp to Frozen draw a trail behind the cursor, as on the Mac. CRT and Bloom change the whole " +
                    "terminal. Custom runs a Ghostty shader file you import; one that doesn't compile is left off.",
            ) {
                choice("Shader", s.shader, Settings.Shader.entries, { it.label }) { sh -> set { it.copy(shader = sh) } }
                row("Import Shader") {
                    Revealed(s.shader == Settings.Shader.Custom) {
                        HatchListRow(
                            "Ghostty shader file",
                            subtitle = if (s.customShader.isBlank()) "None imported" else "${s.customShader.lines().size} lines",
                            trailing = { HatchPillButton("Import", { importShader.launch(arrayOf("*/*")) }) },
                        )
                    }
                }
            }
            section("Rows", footer = "Bottom pins the last row to the pane's bottom edge, so the prompt meets the keyboard.") {
                segmented("Terminal anchoring", s.anchor, Settings.Anchor.entries, { it.name }) { a -> set { it.copy(anchor = a) } }
            }
            section("Scrolling") {
                switch("Smooth scrolling", s.smoothScrolling, "Scroll history by the pixel, not a line at a time, and let a flick keep going, in full-screen programs too.") { on -> set { it.copy(smoothScrolling = on) } }
            }
            section("Titles") {
                switch("Prefer generated titles", s.preferGeneratedTitles, "Show the running program and directory instead of the title a program sets.") { on ->
                    set { it.copy(preferGeneratedTitles = on) }
                }
            }
        }
    }
}

/** A discrete font stepper, independent of Android's system text scale. */
@Composable
private fun FontMetrics(s: Settings, set: ((Settings) -> Settings) -> Unit) {
    Column(Modifier.padding(Hatch.layout.cardPadding), verticalArrangement = Arrangement.spacedBy(Hatch.space.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Hatch.space.xxl)) {
            HatchStepperField(
                "Font size", "${s.fontSize}", HatchIcons.TextSize, "pt",
                s.fontSize > Settings.FontSizes.first, s.fontSize < Settings.FontSizes.last,
                { set { it.copy(fontSize = it.fontSize - 1) } }, { set { it.copy(fontSize = it.fontSize + 1) } },
            )
            HatchStepperField(
                "Line height", "${s.lineHeight}", HatchIcons.LineHeight, "%",
                s.lineHeight > Settings.LineHeights.first, s.lineHeight < Settings.LineHeights.last,
                { set { it.copy(lineHeight = (it.lineHeight - Settings.LINE_HEIGHT_STEP).coerceIn(Settings.LineHeights)) } },
                { set { it.copy(lineHeight = (it.lineHeight + Settings.LINE_HEIGHT_STEP).coerceIn(Settings.LineHeights)) } },
            )
        }
        TerminalPreview(s, Modifier.clip(HatchShapes.Small))
    }
}

@Composable
private fun CursorGlyph(style: CursorStyle, selected: Boolean) {
    val color = if (selected) chrome.accent else chrome.text
    Box(Modifier.size(width = 12.dp, height = 20.dp)) {
        when (style) {
            CursorStyle.Block -> Box(Modifier.fillMaxSize().background(color))
            CursorStyle.Bar -> Box(Modifier.size(2.5.dp, 20.dp).background(color))
            CursorStyle.Underline -> Box(Modifier.align(Alignment.BottomStart).size(12.dp, 2.5.dp).background(color))
        }
    }
}

@Composable
internal fun FontPage(store: SettingsStore, back: (() -> Unit)?) {
    val s by store.settings.collectAsState()
    SettingsPage("Terminal font", back) {
        HatchListCard {
            Settings.FontFamily.entries.forEachIndexed { i, family ->
                if (i > 0) HatchListDivider(Dimens.CardPadding)
                val selected = family == s.fontFamily
                HatchListRow(
                    Modifier.selectable(selected, role = Role.RadioButton) { store.update { it.copy(fontFamily = family) } },
                    trailing = { if (selected) Icon(HatchIcons.Check, null, Modifier.size(Dimens.Icon), tint = chrome.accent) },
                ) {
                    Text(
                        family.label, style = HatchType.Body.copy(fontFamily = family.compose()), color = if (selected) chrome.accent else chrome.text,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        CardSpacer()
        HatchListCard { TerminalPreview(s) }
        Footnote("Each font is shown in itself. The preview updates as you choose.")
    }
}

internal fun seconds(millis: Int): String = String.format(java.util.Locale.ROOT, "%.2fs", millis / 1000.0)
