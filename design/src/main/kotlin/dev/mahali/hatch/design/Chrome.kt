package dev.mahali.hatch.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

@Immutable
public data class Chrome(
    val dark: Boolean,
    /** Theme token surfaceSubtle. */
    val background: Color,
    /** Theme token surface. */
    val surface: Color,
    val card: Color,
    val control: Color,
    val text: Color,
    val secondary: Color,
    /** Theme token contentTertiary. */
    val tertiary: Color,
    val divider: Color,
    val stroke: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val warning: Color,
    val success: Color,
    val info: Color,
    /** Not themed: the same in every theme. */
    val online: Color,
    val terminal: Color,
) {
    public val pressed: Color get() = text.copy(alpha = if (dark) 0.12f else 0.08f)

    public val focus: Color get() = accent.copy(alpha = 0.6f)

    /** Matches the renderer's inactive-pane fade. */
    public val workspace: Color
        get() {
            val fade = if (dark) 0.8f else 0.98f
            return Color(terminal.red * fade, terminal.green * fade, terminal.blue * fade, 1f)
        }

    public companion object {
        /** Sampled from iOS Rex; used when "Tint with theme" is off. */
        public val NeutralLight: Chrome = Chrome(
            dark = false, background = Color(0xFFECEEF2), surface = Color(0xFFF8F9FB), card = Color(0xFFFAFBFC),
            control = Color(0xFFE9EAEB), text = Color(0xFF1F2328), secondary = Color(0xFF696D71), tertiary = Color(0xFF8A8E92), divider = Color(0xFFE7E9EB),
            stroke = Color(0xFFD1D2D5), accent = Color(0xFF0061F6), onAccent = Color.White,
            accentContainer = Color(0xFFE3EFFF), onAccentContainer = Color(0xFF0061F6),
            danger = Color(0xFFE5484D), onDanger = Color.White, warning = Color(0xFFDCA451), success = Color(0xFF5FBC60), info = Color(0xFF4C7BD9),
            online = Color(0xFF188038), terminal = Color(0xFFFFFFFF),
        )

        /** Labels on the dark accent are dark: white reads at only 3.1:1 on it. */
        public val NeutralDark: Chrome = Chrome(
            dark = true, background = Color(0xFF0F0F10), surface = Color(0xFF161618), card = Color(0xFF1E1F22),
            control = Color(0xFF38393C), text = Color(0xFFF2F2F4), secondary = Color(0xFF9A9DA3), tertiary = Color(0xFF75787E), divider = Color(0xFF2C2D31),
            stroke = Color(0xFF3A3B40), accent = Color(0xFF3E91FF), onAccent = Color(0xFF0F0F10),
            accentContainer = Color(0xFF182940), onAccentContainer = Color(0xFF3E91FF),
            danger = Color(0xFFFF6369), onDanger = Color(0xFF0F0F10), warning = Color(0xFFDCA451), success = Color(0xFF6CC56D), info = Color(0xFF8FA6E0),
            online = Color(0xFF4CD964), terminal = Color(0xFF0B0B0C),
        )

        public fun of(theme: TerminalTheme, tint: Boolean): Chrome {
            val base = if (theme.dark) NeutralDark else NeutralLight
            if (!tint) return base.copy(terminal = Color(theme.background))
            fun c(token: String, fallback: Color) = theme.token(token)?.let(::Color) ?: fallback
            val card = c("surfaceRaised", base.card)
            val text = c("content", base.text)
            val accent = c("accent", base.accent)
            val danger = c("danger", base.danger)
            return Chrome(
                dark = theme.dark,
                background = c("surfaceSubtle", base.background),
                surface = c("surface", base.surface),
                card = card,
                // The theme's control token is a 2% hover wash; alpha 0.08/0.12 matches iOS control fills.
                control = c("control", text).copy(alpha = if (theme.dark) 0.12f else 0.08f).compositeOver(card),
                text = text,
                secondary = c("contentSecondary", base.secondary),
                tertiary = c("contentTertiary", base.tertiary),
                divider = c("strokeSubtle", base.divider),
                stroke = c("stroke", base.stroke),
                accent = accent,
                onAccent = readableOn(accent, c("accentOnSolid", base.onAccent)),
                accentContainer = c("accentContainer", base.accentContainer),
                onAccentContainer = c("accentOnContainer", accent),
                danger = danger,
                onDanger = readableOn(danger, c("dangerOnSolid", base.onDanger)),
                warning = c("warning", base.warning),
                success = c("success", base.success),
                info = c("info", base.info),
                online = base.online,
                terminal = Color(theme.background),
            )
        }

        private fun readableOn(fill: Color, preferred: Color): Color =
            if (contrast(preferred, fill) >= 4.5f) preferred
            else listOf(preferred, Color.White, Color.Black).maxBy { contrast(it, fill) }

        private fun contrast(a: Color, b: Color): Float {
            val la = a.luminance()
            val lb = b.luminance()
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }
    }
}

public val LocalChrome: androidx.compose.runtime.ProvidableCompositionLocal<Chrome> = staticCompositionLocalOf { Chrome.NeutralDark }
