package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId

internal class FocusChange(val focusBlock: Boolean, val events: List<Pair<BlockId, Boolean>>)

/** [previous] is null at open or after backgrounding; [serverFocused] is the server's last report. */
internal fun focusChange(previous: BlockId?, next: BlockId, serverFocused: BlockId?): FocusChange = when {
    previous == next -> FocusChange(false, emptyList())
    serverFocused == next -> FocusChange(false, listOfNotNull(previous?.let { it to false }, next to true))
    else -> FocusChange(true, emptyList())
}
