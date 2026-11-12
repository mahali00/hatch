package dev.mahali.hatch.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** No dynamic colour: the Material scheme is mapped onto [chrome] by hand. */
@Composable
public fun HatchTheme(chrome: Chrome, scale: HatchScale = HatchScale.Phone, content: @Composable () -> Unit) {
    val base = if (chrome.dark) darkColorScheme() else lightColorScheme()
    // Container and High share the card: menus, dialogs and sheets are all cards here.
    val scheme = base.copy(
        primary = chrome.accent,
        onPrimary = chrome.onAccent,
        primaryContainer = chrome.accentContainer,
        onPrimaryContainer = chrome.onAccentContainer,
        inversePrimary = chrome.accentContainer,
        secondary = chrome.accent,
        onSecondary = chrome.onAccent,
        secondaryContainer = chrome.control,
        onSecondaryContainer = chrome.text,
        tertiary = chrome.accent,
        onTertiary = chrome.onAccent,
        tertiaryContainer = chrome.accentContainer,
        onTertiaryContainer = chrome.onAccentContainer,
        background = chrome.background,
        onBackground = chrome.text,
        surface = chrome.surface,
        onSurface = chrome.text,
        surfaceVariant = chrome.control,
        onSurfaceVariant = chrome.secondary,
        surfaceDim = chrome.background,
        surfaceBright = chrome.card,
        surfaceContainerLowest = chrome.background,
        surfaceContainerLow = chrome.surface,
        surfaceContainer = chrome.card,
        surfaceContainerHigh = chrome.card,
        surfaceContainerHighest = chrome.control,
        surfaceTint = Color.Transparent,
        inverseSurface = chrome.text,
        inverseOnSurface = chrome.card,
        outline = chrome.stroke,
        outlineVariant = chrome.divider,
        error = chrome.danger,
        onError = chrome.onDanger,
    )
    val typography = remember(scale) { typography(scale) }
    CompositionLocalProvider(LocalChrome provides chrome, LocalHatchScale provides scale, LocalListMetrics provides scale.list) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

public val chrome: Chrome
    @Composable @ReadOnlyComposable get() = LocalChrome.current

private fun typography(s: HatchScale): Typography = with(s.type) {
    Typography(
        displayLarge = largeTitle, displayMedium = largeTitle, displaySmall = largeTitle,
        headlineLarge = largeTitle, headlineMedium = largeTitle, headlineSmall = largeTitle,
        titleLarge = title, titleMedium = title, titleSmall = title,
        bodyLarge = body, bodyMedium = body, bodySmall = callout,
        labelLarge = body.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = footnote,
    )
}
