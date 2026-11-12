package dev.mahali.hatch.engine

import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.protocol.HostSync
import dev.mahali.hatch.protocol.RexApi
import dev.mahali.hatch.protocol.RexError

/** Suspends in the caller's scope; starts no jobs. */
public interface TabOperations {
    public val target: WindowRef
    public suspend fun split(anchor: BlockId, below: Boolean): BlockId?
    public suspend fun close(block: BlockId)
    public suspend fun resize(before: BlockId, after: BlockId, axis: Axis, ratio: Float)
    public suspend fun move(block: BlockId, onto: BlockId, vertical: Boolean, before: Boolean)
    public suspend fun swap(block: BlockId, other: BlockId)
    /** Refreshes this tab's session. True if present, false if gone, null without a connection. */
    public suspend fun refresh(): Boolean?
    /** Focus is the only operation permitted to address a different tab or host. */
    public suspend fun focusOtherTab(destination: WindowRef)
}

/** Shared by Rex and protocol tests. No RPC client escapes it. */
public fun createTabOperations(
    target: WindowRef,
    api: (HostId) -> RexApi?,
    sync: () -> HostSync?,
    startup: () -> String,
): TabOperations = ProtocolTabOperations(target, api, sync, startup)

internal class ProtocolTabOperations(
    override val target: WindowRef,
    private val api: (HostId) -> RexApi?,
    private val sync: () -> HostSync?,
    private val startup: () -> String,
) : TabOperations {
    private fun connection(host: HostId = target.host): RexApi =
        api(host) ?: throw RexError("disconnected", "Host is not connected")

    override suspend fun split(anchor: BlockId, below: Boolean): BlockId? =
        connection().newSplit(target.session, anchor, startup(), below)

    override suspend fun close(block: BlockId) {
        connection().closeBlock(target.session, block)
    }

    override suspend fun resize(before: BlockId, after: BlockId, axis: Axis, ratio: Float) {
        connection().resizePane(target.session, before, after, axis, ratio)
    }

    override suspend fun move(block: BlockId, onto: BlockId, vertical: Boolean, before: Boolean) {
        connection().moveBlock(target.session, block, onto, vertical, before)
    }

    override suspend fun swap(block: BlockId, other: BlockId) {
        connection().swapBlocks(target.session, block, other)
    }

    override suspend fun refresh(): Boolean? {
        val connection = sync() ?: return null
        connection.refresh(target.session)
        return target.session in connection.state.value.sessionsById && target.window in connection.state.value.windowsById
    }

    override suspend fun focusOtherTab(destination: WindowRef) {
        connection(destination.host).focusWindow(destination.session, destination.window)
    }
}
