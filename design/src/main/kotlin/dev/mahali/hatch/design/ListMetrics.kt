package dev.mahali.hatch.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public data class ListMetrics(
    public val inset: Dp,
    public val padding: Dp,
    public val leading: Dp,
    public val gap: Dp,
    public val rowHeight: Dp,
    public val actionIcon: Dp,
    public val chevron: Dp,
    public val headerHeight: Dp,
    public val title: TextStyle,
    public val label: TextStyle,
    public val body: TextStyle,
    public val selectedInset: Dp = 6.dp,
    public val selectedRadius: Dp = 18.dp,
    /** Rex Mac's sidebar style: no chevrons, since a tab opens beside the list, not on a new screen. */
    public val quiet: Boolean = false,
    /** Text opacity of unselected rows, matching Rex Mac's dimmed sidebar. */
    public val restingText: Float = 1f,
    /** Value at the row's end, as Rex Mac's pickers put it. */
    public val choiceTrailing: Boolean = false,
) {
    public companion object {
        public val Standard: ListMetrics @Composable @ReadOnlyComposable get() = LocalHatchScale.current.list
        public val Sidebar: ListMetrics @Composable @ReadOnlyComposable get() = LocalHatchScale.current.sidebar

        internal val PhoneSidebar: ListMetrics = ListMetrics(
            inset = 8.dp, padding = 12.dp, leading = 22.dp,
            gap = 8.dp, rowHeight = 48.dp, actionIcon = 16.dp, chevron = 14.dp,
            headerHeight = 36.dp, selectedInset = 2.dp, quiet = true, restingText = 0.72f,
            title = HatchScale.style(16, 21, FontWeight.Medium),
            label = HatchScale.style(14, 19, FontWeight.Medium),
            body = HatchScale.style(16, 21, FontWeight.Normal),
        )
    }
}

public val LocalListMetrics: androidx.compose.runtime.ProvidableCompositionLocal<ListMetrics> =
    staticCompositionLocalOf { HatchScale.Phone.list }
