package dev.mahali.hatch.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reads the [HatchScale] and [Chrome] that [HatchTheme] provides, so a screen never picks its own numbers. */
public object Hatch {
    public val type: HatchTypography @Composable @ReadOnlyComposable get() = LocalHatchScale.current.type
    public val space: HatchSpacing @Composable @ReadOnlyComposable get() = LocalHatchScale.current.space
    public val size: HatchSizes @Composable @ReadOnlyComposable get() = LocalHatchScale.current.size
    public val layout: HatchLayout @Composable @ReadOnlyComposable get() = LocalHatchScale.current.layout
    public val radius: HatchRadii @Composable @ReadOnlyComposable get() = LocalHatchScale.current.radius
    public val motion: HatchMotion @Composable @ReadOnlyComposable get() = LocalHatchScale.current.motion
    public val colors: Chrome @Composable @ReadOnlyComposable get() = LocalChrome.current
    public val scale: HatchScale @Composable @ReadOnlyComposable get() = LocalHatchScale.current
}

@Immutable
public data class HatchTypography(
    val largeTitle: TextStyle,
    val title: TextStyle,
    val section: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val label: TextStyle,
    val footnote: TextStyle,
    val mono: TextStyle,
)

@Immutable
public data class HatchSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
)

@Immutable
public data class HatchSizes(
    val touchTarget: Dp,
    val control: Dp,
    val controlCompact: Dp,
    val rowTwoLine: Dp,
    val menuRow: Dp,
    val menuWidth: Dp,
    val iconSmall: Dp,
    val icon: Dp,
    val toolbarIcon: Dp,
    val keyBarIcon: Dp,
    val chevron: Dp,
    val smallChevron: Dp,
    val leading: Dp,
    val floatingButton: Dp,
    /** Rex Mac's 30 pt glyph pitch unfolded. */
    val toolbarButton: Dp,
    val appIcon: Dp,
    val imeBar: Dp,
    val switchWidth: Dp,
    val switchHeight: Dp,
)

@Immutable
public data class HatchLayout(
    val screenInset: Dp,
    val cardPadding: Dp,
    val fieldPadding: Dp,
    val iconGap: Dp,
    val menuPaddingH: Dp,
    val menuPaddingV: Dp,
    val menuIconGap: Dp,
    val sectionGap: Dp,
    val headerGap: Dp,
    val paneGap: Dp,
    val imeBarGap: Dp,
    /** Kept below the top safe-drawing inset so the toggle never moves when the sidebar hides. */
    val sidebarToggleStart: Dp,
    val sidebarToggleTop: Dp,
    /** Rex Mac's 215 pt settings column, with touch room. */
    val settingsSidebar: Dp,
)

@Immutable
public data class HatchRadii(
    val small: Dp,
    val card: Dp,
    val sheet: Dp,
    val pane: Dp,
)

/** The values of Rex Mac's Animation style setting. */
public enum class AnimationStyle { Snappy, Natural, Off }

/** Animate with [spec], so Off reaches every animation at once. */
@Immutable
public data class HatchMotion(val style: AnimationStyle = AnimationStyle.Snappy) {
    val quick: Int get() = when (style) { AnimationStyle.Snappy -> 150; AnimationStyle.Natural -> 220; AnimationStyle.Off -> 0 }

    val standard: Int get() = when (style) { AnimationStyle.Snappy -> 250; AnimationStyle.Natural -> 350; AnimationStyle.Off -> 0 }

    public fun <T> spec(millis: Int = standard, delayMillis: Int = 0, easing: Easing = FastOutSlowInEasing): FiniteAnimationSpec<T> =
        if (style == AnimationStyle.Off) snap() else tween(millis, delayMillis, easing)
}

/** Desktop sizes are measured on the Rex Mac client (1 pt there = 1 dp here). */
@Immutable
public data class HatchScale(
    val type: HatchTypography,
    val space: HatchSpacing,
    val size: HatchSizes,
    val layout: HatchLayout,
    val radius: HatchRadii,
    val motion: HatchMotion,
    val list: ListMetrics,
    val sidebar: ListMetrics,
    val unfolded: Boolean,
) {
    public companion object {
        private val Space = HatchSpacing()

        private val OneUiType = HatchTypography(
            largeTitle = style(24, 30, FontWeight.SemiBold),
            title = style(17, 22, FontWeight.SemiBold),
            section = style(14, 19, FontWeight.Medium),
            body = style(17, 22, FontWeight.Normal),
            callout = style(14, 19, FontWeight.Normal),
            label = style(14, 19, FontWeight.Medium),
            footnote = style(13, 17, FontWeight.Normal),
            mono = mono(15, 20),
        )

        /** Sized as One UI's own apps. */
        public val Phone: HatchScale = HatchScale(
            type = OneUiType,
            space = Space,
            size = HatchSizes(
                touchTarget = 48.dp, control = 52.dp, controlCompact = 36.dp,
                rowTwoLine = 72.dp, menuRow = 52.dp, menuWidth = 280.dp,
                iconSmall = 16.dp, icon = 22.dp, toolbarIcon = 20.dp, keyBarIcon = 20.dp, chevron = 18.dp, smallChevron = 16.dp,
                leading = 30.dp, floatingButton = 47.dp, toolbarButton = 48.dp, appIcon = 72.dp, imeBar = 40.dp, switchWidth = 40.dp, switchHeight = 24.dp,
            ),
            layout = HatchLayout(
                screenInset = Space.l, cardPadding = 18.dp, fieldPadding = Space.xl, iconGap = Space.m,
                menuPaddingH = 22.dp, menuPaddingV = Space.s, menuIconGap = Space.l,
                sectionGap = 26.dp, headerGap = Space.s, paneGap = 6.dp, imeBarGap = Space.s,
                sidebarToggleStart = Space.l, sidebarToggleTop = Space.xs, settingsSidebar = 280.dp,
            ),
            radius = HatchRadii(small = 10.dp, card = 24.dp, sheet = 30.dp, pane = 6.dp),
            motion = HatchMotion(),
            list = ListMetrics(
                inset = 16.dp, padding = 18.dp, leading = 32.dp, gap = 14.dp, rowHeight = 60.dp,
                actionIcon = 22.dp, chevron = 18.dp, headerHeight = 48.dp,
                title = OneUiType.title, label = OneUiType.label, body = OneUiType.body,
            ),
            sidebar = ListMetrics.PhoneSidebar,
            unfolded = false,
        )

        /** Rex Mac's 13 pt type read small at phone distance, so the type ramp is One UI's. */
        public val Desktop: HatchScale = HatchScale(
            type = OneUiType,
            space = Space,
            size = HatchSizes(
                touchTarget = 48.dp, control = 48.dp, controlCompact = 36.dp,
                rowTwoLine = 68.dp, menuRow = 48.dp, menuWidth = 260.dp,
                iconSmall = 16.dp, icon = 20.dp, toolbarIcon = 20.dp, keyBarIcon = 18.dp, chevron = 16.dp, smallChevron = 14.dp,
                leading = 28.dp, floatingButton = 48.dp, toolbarButton = 40.dp, appIcon = 64.dp, imeBar = 40.dp, switchWidth = 40.dp, switchHeight = 24.dp,
            ),
            layout = HatchLayout(
                screenInset = Space.xl, cardPadding = Space.l, fieldPadding = Space.l, iconGap = Space.m,
                menuPaddingH = Space.l, menuPaddingV = Space.s, menuIconGap = Space.m,
                sectionGap = Space.xl, headerGap = 6.dp, paneGap = Space.s, imeBarGap = Space.s,
                sidebarToggleStart = Space.m, sidebarToggleTop = Space.xs, settingsSidebar = 280.dp,
            ),
            radius = HatchRadii(small = 8.dp, card = 12.dp, sheet = 16.dp, pane = 6.dp),
            motion = HatchMotion(),
            list = ListMetrics(
                inset = 20.dp, padding = 16.dp, leading = 28.dp, gap = 12.dp, rowHeight = 56.dp,
                actionIcon = 20.dp, chevron = 16.dp, headerHeight = 40.dp, selectedRadius = 12.dp, choiceTrailing = true,
                title = OneUiType.title, label = OneUiType.label, body = OneUiType.body,
            ),
            // The sidebar, sized like One UI's navigation rail lists.
            sidebar = ListMetrics(
                inset = 4.dp, padding = 16.dp, leading = 24.dp, gap = 12.dp, rowHeight = 48.dp,
                actionIcon = 18.dp, chevron = 14.dp, headerHeight = 36.dp, selectedInset = 2.dp, selectedRadius = 20.dp, quiet = true, restingText = 0.72f,
                title = style(16, 21, FontWeight.Medium), label = style(14, 19, FontWeight.Medium), body = style(16, 21, FontWeight.Normal),
            ),
            unfolded = true,
        )

        internal fun style(size: Number, line: Number, weight: FontWeight): TextStyle =
            TextStyle(fontFamily = Inter, fontSize = size.toFloat().sp, lineHeight = line.toFloat().sp, fontWeight = weight, letterSpacing = 0.sp)

        private fun mono(size: Number, line: Number): TextStyle =
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.toFloat().sp, lineHeight = line.toFloat().sp, letterSpacing = 0.sp)
    }
}

public fun HatchScale.adjusted(animation: AnimationStyle, compactPanes: Boolean, sidebarTextStep: Int): HatchScale {
    fun TextStyle.stepped() = if (sidebarTextStep == 0) this else copy(fontSize = (fontSize.value + sidebarTextStep).sp, lineHeight = (lineHeight.value + sidebarTextStep).sp)
    return copy(
        motion = HatchMotion(animation),
        layout = if (compactPanes) layout.copy(paneGap = 0.dp) else layout,
        sidebar = sidebar.copy(title = sidebar.title.stepped(), label = sidebar.label.stepped(), body = sidebar.body.stepped()),
    )
}

public val LocalHatchScale: androidx.compose.runtime.ProvidableCompositionLocal<HatchScale> = staticCompositionLocalOf { HatchScale.Phone }

private val scale: HatchScale @Composable @ReadOnlyComposable get() = LocalHatchScale.current

/** Older names; new code reads [Hatch.type]. */
public object HatchType {
    public val LargeTitle: TextStyle @Composable @ReadOnlyComposable get() = scale.type.largeTitle
    public val Title: TextStyle @Composable @ReadOnlyComposable get() = scale.type.title
    public val Body: TextStyle @Composable @ReadOnlyComposable get() = scale.type.body
    public val Callout: TextStyle @Composable @ReadOnlyComposable get() = scale.type.callout
    public val Subhead: TextStyle @Composable @ReadOnlyComposable get() = scale.type.label
    public val Footnote: TextStyle @Composable @ReadOnlyComposable get() = scale.type.footnote
}

public object HatchShapes {
    public val CardRadius: Dp @Composable @ReadOnlyComposable get() = scale.radius.card
    public val SmallRadius: Dp @Composable @ReadOnlyComposable get() = scale.radius.small
    public val PaneRadius: Dp @Composable @ReadOnlyComposable get() = scale.radius.pane
    public val SheetRadius: Dp @Composable @ReadOnlyComposable get() = scale.radius.sheet

    public val Card: Shape @Composable @ReadOnlyComposable get() = RoundedCornerShape(CardRadius)

    public val Capsule: Shape = CircleShape

    public val Small: Shape @Composable @ReadOnlyComposable get() = RoundedCornerShape(SmallRadius)

    public val Sheet: Shape @Composable @ReadOnlyComposable get() = RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius)
}

/** Older names; new code reads [Hatch.size], [Hatch.layout] and [Hatch.space]. */
public object Dimens {
    public val PaneGap: Dp @Composable @ReadOnlyComposable get() = scale.layout.paneGap
    public val ScreenInset: Dp @Composable @ReadOnlyComposable get() = scale.layout.screenInset
    public val CardPadding: Dp @Composable @ReadOnlyComposable get() = scale.layout.cardPadding
    public val FieldPadding: Dp @Composable @ReadOnlyComposable get() = scale.layout.fieldPadding
    public val IconGap: Dp @Composable @ReadOnlyComposable get() = scale.layout.iconGap
    public val LeadingWidth: Dp @Composable @ReadOnlyComposable get() = scale.size.leading

    public val TwoLineRowHeight: Dp @Composable @ReadOnlyComposable get() = scale.size.rowTwoLine
    public val MenuRowHeight: Dp @Composable @ReadOnlyComposable get() = scale.size.menuRow
    public val MenuWidth: Dp @Composable @ReadOnlyComposable get() = scale.size.menuWidth
    public val MenuPaddingH: Dp @Composable @ReadOnlyComposable get() = scale.layout.menuPaddingH
    public val MenuPaddingV: Dp @Composable @ReadOnlyComposable get() = scale.layout.menuPaddingV
    public val MenuIconGap: Dp @Composable @ReadOnlyComposable get() = scale.layout.menuIconGap

    public val SectionHeaderStart: Dp @Composable @ReadOnlyComposable get() = ScreenInset + scale.space.l
    public val SectionGap: Dp @Composable @ReadOnlyComposable get() = scale.layout.sectionGap
    public val HeaderGap: Dp @Composable @ReadOnlyComposable get() = scale.layout.headerGap

    public val Icon: Dp @Composable @ReadOnlyComposable get() = scale.size.icon
    public val Chevron: Dp @Composable @ReadOnlyComposable get() = scale.size.chevron
    public val SmallChevron: Dp @Composable @ReadOnlyComposable get() = scale.size.smallChevron
    public val TouchTarget: Dp @Composable @ReadOnlyComposable get() = scale.size.touchTarget
    public val FloatingButton: Dp @Composable @ReadOnlyComposable get() = scale.size.floatingButton
    public val ImeBarHeight: Dp @Composable @ReadOnlyComposable get() = scale.size.imeBar
    public val ImeBarGap: Dp @Composable @ReadOnlyComposable get() = scale.layout.imeBarGap
    public val ControlHeight: Dp @Composable @ReadOnlyComposable get() = scale.size.control
    public val SidebarToggleStart: Dp @Composable @ReadOnlyComposable get() = scale.layout.sidebarToggleStart
    public val SidebarToggleTop: Dp @Composable @ReadOnlyComposable get() = scale.layout.sidebarToggleTop
}
