package dev.mahali.hatch.core

public data class GridSize(val cols: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int)

/** A cell on screen, by viewport column and row from the top-left. */
public data class Cell(val column: Int, val row: Int)

public data class Scroll(val offset: Int = 0, val total: Int = 0, val rows: Int = 0) {
    val scrolledBack: Boolean get() = offset + rows < total
}

public data class Modes(
    val alternateScreen: Boolean = false,
    val mouseTracking: Boolean = false,
    val alternateScroll: Boolean = false,
)

/** Published as one value per frame, so a reader never mixes fields from different frames. */
public data class PaneGeometry(
    val requested: GridSize? = null,
    val applied: GridSize? = null,
    val displayed: GridSize? = null,
    val cursor: Cell? = null,
    val scroll: Scroll = Scroll(),
    val modes: Modes = Modes(),
)

/** Throttles frames, never parsing: in-band messages and the resume offset depend on byte order. */
public enum class PanePriority {
    Focused,
    Visible,
    Background,
}

public sealed interface HostFailure {
    /** Not an error: the state already shows it. */
    public data object Gone : HostFailure

    public data object Disconnected : HostFailure

    public data object Timeout : HostFailure

    public data class Rejected(val code: String, val message: String) : HostFailure

    public companion object {
        public fun of(code: String, message: String = ""): HostFailure = when (code) {
            "not_found", "session_not_found" -> Gone
            "disconnected" -> Disconnected
            "timeout" -> Timeout
            else -> Rejected(code, message)
        }
    }
}
