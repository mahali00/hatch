package dev.mahali.hatch.feature.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.engine.PaneLease
import dev.mahali.hatch.engine.TerminalColors
import kotlinx.coroutines.flow.filterNotNull

/** Binds one tab's visible grids and focus to its lease, and releases it on disposal. */
@Composable
internal fun PaneLeaseEffects(
    lease: PaneLease,
    tab: TabController,
    viewport: TerminalViewport,
    settled: BlockId?,
    colors: TerminalColors,
    active: Boolean,
) {
    DisposableEffect(lease) { onDispose { lease.close() } }

    val claimNow by rememberUpdatedState(settled to colors)
    LaunchedEffect(lease) {
        snapshotFlow {
            // A layout switch reveals panes before their grids change; wait so claims never send the old mode's sizes.
            if (viewport.layoutMode != tab.mode) return@snapshotFlow null
            val shown = tab.claims(claimNow.first) {
                viewport.placed.value.filter { it.visible }.map { it.block }
            }
            Triple(shown, viewport.grids, claimNow.second)
        }.filterNotNull().collect { (shown, grids, palette) ->
            lease.want(shown, grids, palette)
        }
    }

    LaunchedEffect(settled, tab.parked, active) {
        when {
            tab.parked -> lease.background()
            active && settled != null -> lease.focus(settled)
        }
    }
}
