package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.core.BlockId

/** Identity owns selection. Indices only cross the pager boundary with the order they came from. */
internal data class PaneSelection(
    val panes: List<BlockId> = emptyList(),
    val server: BlockId? = null,
    val local: BlockId? = null,
    val pending: PaneTransition? = null,
    val version: Long = 0,
    /** The pane shown before the last local change, for Last Pane (tmux last-pane). */
    val previous: BlockId? = null,
) {
    val current: BlockId? get() = pending?.block?.takeIf { it in panes } ?: local?.takeIf { it in panes }
        ?: server?.takeIf { it in panes } ?: panes.firstOrNull()

    fun reconcile(order: List<BlockId>, focused: BlockId?): PaneSelection {
        if (order == panes && focused == server) return this
        val request = pending?.takeUnless { it.block in panes && it.block !in order }
        return copy(
            panes = order.toList(), server = focused, local = local?.takeIf { it in order },
            pending = request, version = version + 1, previous = previous?.takeIf { it in order },
        )
    }

    fun choose(block: BlockId): PaneSelection = copy(local = block, pending = null, version = version + 1, previous = leaving(block))

    fun request(block: BlockId): PaneSelection = copy(
        local = block.takeIf { it in panes } ?: local,
        pending = PaneTransition(block, version + 1), version = version + 1, previous = leaving(block),
    )

    /** Back to [previous]; again returns. Null when there is none. */
    fun last(): PaneSelection? = previous?.takeIf { it in panes && it != current }?.let(::request)

    private fun leaving(block: BlockId) = current?.takeIf { it != block } ?: previous

    fun complete(request: PaneTransition): PaneSelection =
        if (pending == request) copy(pending = null) else this

    fun boundary(): PagerBoundary = PagerBoundary(panes, version)

    fun swipe(boundary: PagerBoundary, index: Int): PaneSelection {
        if (boundary.version != version || boundary.panes != panes) return this
        return boundary.panes.getOrNull(index)?.let(::choose) ?: this
    }
}

internal data class PaneTransition(val block: BlockId, val version: Long)
internal data class PagerBoundary(val panes: List<BlockId>, val version: Long)
