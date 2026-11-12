package dev.mahali.hatch.feature.terminal

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.min

internal enum class Pull { Idle, Armed }

internal fun pullState(pull: Float, threshold: Float): Pull = if (pull >= threshold) Pull.Armed else Pull.Idle

// A quick swipe on the last page reaches the threshold too; [maxSpeed] stops it splitting by accident.
internal fun splitsOnRelease(pull: Float, threshold: Float, speed: Float = 0f, maxSpeed: Float = Float.MAX_VALUE): Boolean =
    pullState(pull, threshold) == Pull.Armed && speed <= maxSpeed

// The page follows the pull at RESISTANCE, capped, so it feels held back.
internal fun resisted(pull: Float, cap: Float): Float = min(pull * RESISTANCE, cap)

private const val RESISTANCE = 0.35f

// Pull past the last pane to split, as on iOS. The pager's own scroll and fling pass straight through; nothing here allocates per frame.
@Stable
internal class PullToSplit(private val threshold: Float, private val cap: Float, private val maxReleaseSpeed: Float) : OverscrollEffect {
    var pull by mutableFloatStateOf(0f)
        private set

    val progress: Float get() = (pull / threshold).coerceIn(0f, 1f)

    val armed by derivedStateOf { pullState(pull, threshold) == Pull.Armed }

    // Set by the screen on each composition.
    var onSplit: () -> Unit = {}

    override val isInProgress: Boolean get() = pull != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        // Back towards the panes: undo the pull before the pager moves.
        if (pull > 0f && delta.x > 0f) {
            val back = min(delta.x, pull)
            pull -= back
            return performScroll(Offset(delta.x - back, delta.y)) + Offset(back, 0f)
        }
        val consumed = performScroll(delta)
        val left = delta.x - consumed.x
        // Only the finger pulls; a fling that reaches the end stops there.
        if (left < 0f && source == NestedScrollSource.UserInput) {
            pull -= left
            return Offset(delta.x, consumed.y)
        }
        return consumed
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (pull == 0f) {
            performFling(velocity)
            return
        }
        val split = splitsOnRelease(pull, threshold, kotlin.math.abs(velocity.x), maxReleaseSpeed)
        performFling(Velocity.Zero)
        if (split) onSplit()
        animate(pull, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> pull = value }
    }

    override val node: DelegatableNode = SlideNode()

    private inner class SlideNode : Modifier.Node(), LayoutModifierNode {
        private val slide: GraphicsLayerScope.() -> Unit = { translationX = -resisted(pull, cap) }

        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = slide) }
        }
    }
}
