package dev.mahali.hatch.feature.terminal

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

// `key` is the name sent on the wire.
internal enum class Arrow(val key: String) { Up("arrow_up"), Down("arrow_down"), Left("arrow_left"), Right("arrow_right") }

// degrees: clockwise from screen-right (y down). Diagonals send both arrows.
internal class Direction(val degrees: Float, val arrows: List<Arrow>)

private val directions = listOf(
    Direction(0f, listOf(Arrow.Right)),
    Direction(45f, listOf(Arrow.Down, Arrow.Right)),
    Direction(90f, listOf(Arrow.Down)),
    Direction(135f, listOf(Arrow.Down, Arrow.Left)),
    Direction(180f, listOf(Arrow.Left)),
    Direction(225f, listOf(Arrow.Up, Arrow.Left)),
    Direction(270f, listOf(Arrow.Up)),
    Direction(315f, listOf(Arrow.Up, Arrow.Right)),
)

internal fun joystickDirection(dx: Float, dy: Float, deadZone: Float): Direction? {
    if (hypot(dx, dy) < deadZone) return null
    val sector = (atan2(dy, dx) / (PI / 4)).roundToInt().mod(8)
    return directions[sector]
}
