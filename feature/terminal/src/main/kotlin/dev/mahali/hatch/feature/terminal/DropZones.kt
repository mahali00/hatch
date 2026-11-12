package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.core.Rect

internal enum class DropZone { Left, Right, Top, Bottom, Centre }

internal fun dropZone(x: Float, y: Float, width: Float, height: Float): DropZone {
    val u = x / width
    val v = y / height
    if (u in THIRD..2 * THIRD && v in THIRD..2 * THIRD) return DropZone.Centre
    return when (minOf(u, 1 - u, v, 1 - v)) {
        u -> DropZone.Left
        1 - u -> DropZone.Right
        v -> DropZone.Top
        else -> DropZone.Bottom
    }
}

private const val THIRD = 1f / 3

// Maps to session.move_block's arguments.
internal data class Placement(val vertical: Boolean, val before: Boolean)

// The centre swaps, so it has no placement.
internal fun DropZone.placement(): Placement? = when (this) {
    DropZone.Left -> Placement(vertical = false, before = true)
    DropZone.Right -> Placement(vertical = false, before = false)
    DropZone.Top -> Placement(vertical = true, before = true)
    DropZone.Bottom -> Placement(vertical = true, before = false)
    DropZone.Centre -> null
}

internal fun DropZone.part(): Rect = when (this) {
    DropZone.Left -> Rect(0f, 0f, 0.5f, 1f)
    DropZone.Right -> Rect(0.5f, 0f, 0.5f, 1f)
    DropZone.Top -> Rect(0f, 0f, 1f, 0.5f)
    DropZone.Bottom -> Rect(0f, 0.5f, 1f, 0.5f)
    DropZone.Centre -> Rect(0f, 0f, 1f, 1f)
}
