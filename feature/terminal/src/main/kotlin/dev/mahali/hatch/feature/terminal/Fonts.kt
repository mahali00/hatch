package dev.mahali.hatch.feature.terminal

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.R
import dev.mahali.hatch.renderer.TerminalFont

internal object Fonts {
    private val faces = mutableMapOf<Int, Typeface>()
    fun terminal(context: Context, settings: Settings): TerminalFont {
        // A point is a dp here, as on iOS; the system font scale does not apply.
        val px = settings.fontSize * context.resources.displayMetrics.density
        val spacing = 1f + settings.lineHeight / 100f
        fun font(id: Int) = faces.getOrPut(id) {
            withSymbols(context, id) ?: ResourcesCompat.getFont(context, id) ?: Typeface.MONOSPACE
        }
        return when (settings.fontFamily) {
            Settings.FontFamily.JetBrainsMono -> TerminalFont(
                regular = font(R.font.jetbrains_mono_regular), bold = font(R.font.jetbrains_mono_bold),
                italic = font(R.font.jetbrains_mono_italic), boldItalic = font(R.font.jetbrains_mono_bold_italic), sizePx = px, lineSpacing = spacing,
            )
            Settings.FontFamily.GeistMono -> font(R.font.geist_mono_regular).let { r ->
                TerminalFont(regular = r, bold = font(R.font.geist_mono_bold), sizePx = px, lineSpacing = spacing)
            }
            Settings.FontFamily.RobotoMono -> font(R.font.roboto_mono_regular).let { r ->
                TerminalFont(regular = r, bold = font(R.font.roboto_mono_bold), sizePx = px, lineSpacing = spacing)
            }
            Settings.FontFamily.SystemMonospace -> TerminalFont(regular = Typeface.MONOSPACE, sizePx = px, lineSpacing = spacing)
        }
    }

    // Monochrome symbol font first: otherwise a symbol like Claude Code's "⏺" falls to the colour emoji font. Android 10+.
    private fun withSymbols(context: Context, id: Int): Typeface? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            fun family(res: Int) = FontFamily.Builder(Font.Builder(context.resources, res).build()).build()
            Typeface.CustomFallbackBuilder(family(id))
                .addCustomFallback(family(R.font.noto_sans_symbols2))
                .addCustomFallback(family(R.font.symbols_nerd_font_mono))
                .setSystemFallback("monospace")
                .build()
        }.getOrNull()
    }
}
