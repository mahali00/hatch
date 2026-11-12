package dev.mahali.hatch.feature.sessions

import kotlin.math.floor

/**
 * The slot a row held from [from] lands in when dragged [dragY] px, among [count] rows [rowHeight] tall: the slot under its
 * centre, or null once that centre is over half a row beyond the first or last row, where a drop snaps back.
 */
internal fun reorderTarget(from: Int, dragY: Float, rowHeight: Float, count: Int): Int? {
    if (count <= 0 || rowHeight <= 0f || from !in 0 until count) return null
    val centre = (from + 0.5f) * rowHeight + dragY
    if (centre < -rowHeight / 2 || centre > (count + 0.5f) * rowHeight) return null
    return floor(centre / rowHeight).toInt().coerceIn(0, count - 1)
}

/** Slots row [index] steps (-1 up, +1 down) to open the gap at [to] for the row held from [from]. */
internal fun reorderShift(index: Int, from: Int, to: Int?): Int = when {
    to == null || index == from -> 0
    index in (from + 1)..to -> -1
    index in to until from -> 1
    else -> 0
}

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** What the item lands just before once moved from [from] to [to], or null for last: Rex's move_window takes a "before". */
internal fun <T> List<T>.beforeOnceMoved(from: Int, to: Int): T? = moved(from, to).getOrNull(to + 1)

/** List scroll speed (negative scrolls up) for a finger at [y], ramping to [max] across a [zone] inside [top] and [bottom]. */
internal fun edgeScrollSpeed(y: Float, top: Float, bottom: Float, zone: Float, max: Float): Float = when {
    zone <= 0f -> 0f
    y < top + zone -> -max * ((top + zone - y) / zone).coerceAtMost(1f)
    y > bottom - zone -> max * ((y - (bottom - zone)) / zone).coerceAtMost(1f)
    else -> 0f
}
