package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.Hatch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.Themes
import dev.mahali.hatch.design.chrome
import kotlinx.coroutines.delay

@Composable
internal fun TerminalPreview(s: Settings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = chrome.dark
    val theme = remember(dark, s.lightTheme, s.darkTheme) { Themes.find(context, if (dark) s.darkTheme else s.lightTheme, dark) }
    fun ansi(i: Int) = Color(theme.ansi[i])
    val size = (s.fontSize / LocalDensity.current.fontScale).sp
    val style = TextStyle(color = Color(theme.foreground), fontFamily = s.fontFamily.compose(), fontSize = size, lineHeight = size * 1.35f * (1f + s.lineHeight / 100f))

    Column(
        modifier.fillMaxWidth().background(Color(theme.background)).padding(start = Hatch.space.l, end = Hatch.space.l, top = 18.dp, bottom = Hatch.space.l)
            .clearAndSetSemantics {},
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ansi(2))) { append("user@host") }
                append(":~$ echo \"Hello, World!\"")
            },
            style = style, maxLines = 1, softWrap = false,
        )
        Text("Hello, World!", style = style)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = ansi(5))) { append("→ ≠ ⇒ ") }
                    withStyle(SpanStyle(color = ansi(3), fontWeight = FontWeight.Bold)) { append("**bold**") }
                    append(" ")
                    withStyle(SpanStyle(color = ansi(6), fontStyle = FontStyle.Italic)) { append("_italic_ ") }
                },
                style = style, maxLines = 1, softWrap = false,
            )
            Cursor(s.cursorStyle, s.blink, Color(theme.cursor), style)
        }
    }
}

@Composable
private fun Cursor(shape: Settings.CursorStyle, blink: Settings.Blink, color: Color, style: TextStyle) {
    val density = LocalDensity.current
    val cell = with(density) { style.fontSize.toDp() }
    val periodMs = when (blink) {
        Settings.Blink.Off -> 0
        Settings.Blink.Slow -> 1600
        Settings.Blink.Normal -> 1000
        Settings.Blink.Fast -> 500
    }
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(periodMs) {
        on = true
        while (periodMs > 0) {
            delay(periodMs / 2L)
            on = !on
        }
    }
    val width = cell * 0.6f
    val height = cell * 1.25f
    Box(Modifier.width(width).height(height).alpha(if (on) 1f else 0f)) {
        when (shape) {
            Settings.CursorStyle.Block -> Box(Modifier.width(width).height(height).background(color))
            Settings.CursorStyle.Bar -> Box(Modifier.width(2.dp).height(height).background(color))
            Settings.CursorStyle.Underline -> Box(Modifier.align(Alignment.BottomStart).width(width).height(2.dp).background(color))
        }
    }
}
