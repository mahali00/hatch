package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.ButtonSize
import dev.mahali.hatch.design.LocalListMetrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.FloatingButton
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.floatingBarFill

// Pill = two 47 targets (94 wide); iOS draws it 120 x 48 with glyphs 61.5 apart.
private val BarShape = HatchShapes.Capsule

@Composable
internal fun PhoneBar(activity: Boolean, onToggleActivity: () -> Unit, onOpenSettings: () -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier, onCommands: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(BarShape).background(floatingBarFill()), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(HatchIcons.Settings, "Settings", onOpenSettings, size = ButtonSize.Bar)
            HeaderButton(if (activity) HatchIcons.EyeFilled else HatchIcons.Eye, "Activity", onToggleActivity, size = ButtonSize.Bar, selected = activity)
            // The command palette, for phones without ⇧⌘P: the terminal's ⋯ menu opens it too.
            if (onCommands != null) HeaderButton(HatchIcons.MoreHorizontal, "Commands", onCommands, size = ButtonSize.Bar)
        }
        Spacer(Modifier.weight(1f))
        SearchButton(onSearch)
    }
}

/** Mac groups its toolbar glyphs 30 pt apart; search stays at the bottom, in thumb reach. */
@Composable
internal fun SidebarToolbar(
    activity: Boolean,
    onToggleActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(start = LocalListMetrics.current.inset, end = LocalListMetrics.current.inset, top = Dimens.SidebarToggleTop, bottom = Hatch.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(Dimens.TouchTarget))
        Spacer(Modifier.weight(1f))
        HeaderButton(if (activity) HatchIcons.EyeFilled else HatchIcons.Eye, "Activity", onToggleActivity, size = ButtonSize.Toolbar, selected = activity)
        HeaderButton(HatchIcons.Settings, "Settings", onOpenSettings, size = ButtonSize.Toolbar)
    }
}

@Composable
internal fun SearchButton(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    FloatingButton(HatchIcons.Search, "Search", onSearch, modifier)
}

