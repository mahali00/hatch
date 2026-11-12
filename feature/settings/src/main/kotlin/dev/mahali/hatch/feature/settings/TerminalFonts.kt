package dev.mahali.hatch.feature.settings

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.R


private val jetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_italic, style = FontStyle.Italic),
    Font(R.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
)
private val geistMono = FontFamily(Font(R.font.geist_mono_regular), Font(R.font.geist_mono_bold, FontWeight.Bold))
private val robotoMono = FontFamily(Font(R.font.roboto_mono_regular), Font(R.font.roboto_mono_bold, FontWeight.Bold))

internal fun Settings.FontFamily.compose(): FontFamily = when (this) {
    Settings.FontFamily.SystemMonospace -> FontFamily.Monospace
    Settings.FontFamily.JetBrainsMono -> jetBrainsMono
    Settings.FontFamily.GeistMono -> geistMono
    Settings.FontFamily.RobotoMono -> robotoMono
}
