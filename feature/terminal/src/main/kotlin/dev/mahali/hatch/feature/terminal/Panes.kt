package dev.mahali.hatch.feature.terminal

import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Rect

internal val SettleSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f)

// Neighbours stay composed so a swipe renders them live and their streams are open before they show.
@Composable
internal fun PanePager(state: PagerState, panes: List<BlockId>, pull: PullToSplit, modifier: Modifier = Modifier, pane: @Composable (BlockId) -> Unit) {
    HorizontalPager(
        state,
        modifier,
        beyondViewportPageCount = 1,
        // The tab's pane list can grow a frame before this list does; indexing it crashed on split.
        key = { panes.getOrNull(it)?.value ?: "pending-$it" },
        flingBehavior = PagerDefaults.flingBehavior(state, snapAnimationSpec = SettleSpec),
        overscrollEffect = pull,
    ) { page -> panes.getOrNull(page)?.let { pane(it) } }
}

// Six panes at most, each at least 200x140 dp: a quarter of the Fold's height still shows seven rows.
// Keeps a 2x2 grid live beside a sidebar on the Fold's 750 dp inner screen.
internal fun tiles(rects: Collection<Rect>, width: Float, height: Float): Boolean =
    rects.size in 1..MAX_TILES && rects.all { it.width * width >= MIN_TILE_WIDTH && it.height * height >= MIN_TILE_HEIGHT }

private const val MAX_TILES = 6
private const val MIN_TILE_WIDTH = 200f
private const val MIN_TILE_HEIGHT = 140f
