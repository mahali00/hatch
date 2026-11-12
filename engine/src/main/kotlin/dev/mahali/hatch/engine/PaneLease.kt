package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.allBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The connection identity changes on replacement; generation changes on every welcome. */
internal data class LeaseHost(
    val state: HostState?,
    val connection: Long = 0,
    val generation: Long = 0,
    val load: InputLoad = InputLoad(),
    val capacityVersion: Long = 0,
)

/** A lease can observe its host and submit controls. It cannot reach storage, views, or raw RPCs. */
internal class LeaseCalls(
    val updates: Flow<LeaseHost>,
    val submit: (Long, Command.Control) -> CommandSubmission,
    val cleanup: (Long, List<Command.Control>) -> Unit = { connection, commands ->
        commands.forEach { submit(connection, it) }
    },
)

/** Main thread only. Closing revokes observation before releasing controls; late replies cannot revive the lease. */
public class PaneLease internal constructor(
    private val calls: LeaseCalls,
    private val session: SessionId,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private data class Sizing(val grid: GridSize, val colors: TerminalColors)
    private data class Want(val visible: Set<BlockId>, val sizes: Map<BlockId, Sizing>)
    private class PendingSize(val size: Sizing, val epoch: Long)
    private class PendingFocus(val target: BlockId?, val epoch: Long)
    private data class FocusDispatch(val target: BlockId?)

    private val claims = mutableMapOf<BlockId, Claim>()
    private val acknowledged = mutableMapOf<BlockId, Sizing>()
    private val pendingSizes = mutableMapOf<BlockId, PendingSize>()
    private val pendingClaims = mutableMapOf<BlockId, PendingSize>()
    private val possiblyOwned = mutableSetOf<BlockId>()
    private val releaseWanted = mutableSetOf<BlockId>()
    private val pendingReleases = mutableMapOf<BlockId, Any>()
    private val watchers = mutableSetOf<Job>()
    private var wanted: Want? = null
    private var host: LeaseHost? = null
    private var epoch = 0L
    private var closed = false
    private var waitingForCapacity = false

    private var focusTarget: BlockId? = null
    private var focused: BlockId? = null
    private var pendingFocus: PendingFocus? = null
    private var dispatchedFocus: FocusDispatch? = null
    private val possiblyFocused = linkedSetOf<BlockId>()

    private val following = scope.launch { calls.updates.collect(::onHost) }

    public fun want(visible: Collection<BlockId>, grids: Map<BlockId, GridSize>, colors: TerminalColors) {
        if (closed) return
        wanted = Want(visible.toSet(), grids.mapValues { Sizing(it.value, colors) })
        reconcile()
    }

    public fun focus(block: BlockId?) {
        if (closed) return
        focusTarget = block
        block?.let(::retryClaim)
        reconcile()
        reconcileFocus()
    }

    public fun touched(block: BlockId) {
        if (closed) return
        retryClaim(block)
        reconcile()
        reconcileFocus()
    }

    public fun focusedByServer(block: BlockId) {
        if (closed) return
        pendingFocus = null
        focused = block
        dispatchedFocus = FocusDispatch(block)
        focusTarget = block
        possiblyFocused.clear()
        possiblyFocused += block
    }

    public fun background() {
        if (closed) return
        focusTarget = null
        reconcileFocus()
        wanted = null
        releaseHeld()
    }

    override fun close() {
        if (closed) return
        closed = true
        epoch++
        following.cancel()
        watchers.toList().forEach { it.cancel() }
        watchers.clear()
        val focusToRelease = possiblyFocused + listOfNotNull(dispatchedFocus?.target, focused)
        val held = releaseWanted + possiblyOwned
        // The host outlives this presentation: it retains rejected teardown and orders it before any new claim.
        host?.let { observed ->
            calls.cleanup(observed.connection, focusToRelease.map { Command.Control.Focus(session, it, false) } +
                if (held.isNotEmpty()) listOf(Command.Control.Release(session, held)) else emptyList())
        }
        claims.clear()
        pendingSizes.clear()
        pendingClaims.clear()
        acknowledged.clear()
        pendingReleases.clear()
        releaseWanted.clear()
        possiblyOwned.clear()
        pendingFocus = null
        focused = null
        dispatchedFocus = null
        possiblyFocused.clear()
        wanted = null
    }

    private fun onHost(next: LeaseHost) {
        if (closed) return
        val before = host
        host = next
        val changedConnection = before != null && (
            before.connection != next.connection || before.generation != next.generation ||
                before.state?.link != Link.Online && next.state?.link == Link.Online
            )
        if (changedConnection) {
            epoch++
            watchers.toList().forEach { it.cancel() }
            watchers.clear()
            claims.clear()
            pendingSizes.clear()
            pendingClaims.clear()
            pendingReleases.clear()
            acknowledged.clear()
            pendingFocus = null
            focused = null
            dispatchedFocus = null
            possiblyFocused.clear()
            waitingForCapacity = false
            if (before?.connection != next.connection) {
                // A replacement host can be a different server. Never release old IDs there.
                possiblyOwned.clear()
                releaseWanted.clear()
            }
        }
        val state = next.state ?: return
        var dropped = false
        for ((block, claim) in claims.toMap()) {
            val observed = claim.observe(state.blocksById[block]?.owner, state.clientId)
            if (observed == claim) continue
            claims[block] = observed
            if (observed == Claim.None || observed == Claim.Lost) {
                acknowledged.remove(block)
                pendingSizes.remove(block)
                pendingClaims.remove(block)
                if (observed == Claim.None) dropped = true
            }
        }
        val capacityRecovered = waitingForCapacity && before != null &&
            (next.capacityVersion != before.capacityVersion ||
                next.load.batches < before.load.batches || next.load.bytes < before.load.bytes)
        if (before == null || changedConnection || dropped || capacityRecovered) {
            waitingForCapacity = false
            reconcile()
            reconcileFocus()
        }
    }

    private fun reconcile() {
        if (closed || host == null) return
        val want = wanted
        // Empty visibility is an unresolved swipe, not a request to release everything.
        if (want != null && want.visible.isNotEmpty()) {
            val gone = possiblyOwned.filter { it !in want.visible }.toSet()
            releaseWanted += gone
            for (block in want.visible) {
                releaseWanted.remove(block)
                // A returning pane claims after its admitted release; the old receipt must not erase the new claim.
                if (pendingReleases.remove(block) != null) forget(block)
            }
        }
        reconcileReleases()
        if (want == null) return
        val resized = linkedMapOf<BlockId, Sizing>()
        for (block in want.visible) {
            val size = want.sizes[block] ?: continue
            val claim = claims[block] ?: Claim.None
            if (claim == Claim.Lost) continue
            if (pendingSizes[block]?.size == size || claim != Claim.None && acknowledged[block] == size) continue
            if (claim == Claim.None) {
                submitSizes(Command.Control.Claim(session, mapOf(block to size.grid), size.colors), mapOf(block to size))
            } else resized[block] = size
        }
        if (resized.isNotEmpty()) {
            val colors = resized.values.first().colors
            val recolor = resized.any { (block, size) -> acknowledged[block]?.colors != size.colors }
            submitSizes(
                Command.Control.Resize(session, resized.mapValues { it.value.grid }, colors.takeIf { recolor }),
                resized,
            )
        }
    }

    private fun submitSizes(command: Command.Control, sizes: Map<BlockId, Sizing>) {
        when (val submission = submit(command)) {
            is CommandSubmission.Rejected -> {
                if (submission.reason == AdmissionFailure.Capacity) waitingForCapacity = true
            }
            is CommandSubmission.Accepted -> {
                val pending = sizes.mapValues { PendingSize(it.value, epoch) }
                pendingSizes.putAll(pending)
                if (command is Command.Control.Claim) {
                    pendingClaims.putAll(pending)
                    sizes.keys.forEach { claims[it] = Claim.Sent }
                    possiblyOwned += sizes.keys
                }
                watch(submission.receipt) { outcome ->
                    for ((block, request) in pending) {
                        if (request.epoch != epoch) continue
                        if (command is Command.Control.Claim && pendingClaims[block] === request) {
                            pendingClaims.remove(block)
                            // Claim and sizing have different evidence. A newer resize reply
                            // cannot turn a failed older ownership request into a held claim.
                            if (outcome != CommandOutcome.Acknowledged && claims[block] == Claim.Sent) {
                                claims.remove(block)
                            }
                        }
                        if (pendingSizes[block] !== request) continue
                        pendingSizes.remove(block)
                        if (outcome == CommandOutcome.Acknowledged) {
                            acknowledged[block] = request.size
                        } else {
                            acknowledged.remove(block)
                            // A server owner observation can already have confirmed the claim.
                            // Never replace that evidence with the result of a failed size request.
                        }
                    }
                    // No automatic replay: a rejected RPC must not create a retry loop.
                }
            }
        }
    }

    private fun retryClaim(block: BlockId) {
        val claim = claims[block] ?: Claim.None
        val next = claim.onFocus()
        if (next == claim) return
        claims[block] = next
        acknowledged.remove(block)
        pendingSizes.remove(block)
        pendingClaims.remove(block)
    }

    private fun reconcileFocus() {
        if (closed || host == null) return
        val target = focusTarget
        if (pendingFocus?.target == target && pendingFocus != null) return
        if (pendingFocus == null && focused == target && dispatchedFocus?.target == target &&
            (target != null || possiblyFocused.isEmpty())) return
        // An admitted focus-out deliberately targets null. It is not "no dispatch information".
        val dispatch = dispatchedFocus
        val previous = if (dispatch != null) dispatch.target else focused
        val commands = if (target == null) {
            (possiblyFocused + listOfNotNull(previous)).map { Command.Control.Focus(session, it, false) }
        } else {
            val change = focusChange(previous, target, serverFocused(target))
            change.events.map { (block, on) -> Command.Control.Focus(session, block, on) } +
                if (change.focusBlock) listOf(Command.Control.Select(session, target)) else emptyList()
        }
        if (commands.isEmpty()) return
        val pending = PendingFocus(target, epoch)
        pendingFocus = pending
        val receipts = mutableListOf<Deferred<CommandOutcome>>()
        var rejected = false
        for (command in commands) {
            when (val submission = submit(command)) {
                is CommandSubmission.Rejected -> {
                    rejected = true
                    if (submission.reason == AdmissionFailure.Capacity) waitingForCapacity = true
                }
                is CommandSubmission.Accepted -> {
                    receipts += submission.receipt
                    when (command) {
                        is Command.Control.Select -> possiblyFocused += command.block
                        is Command.Control.Focus -> if (command.focused) possiblyFocused += command.block
                        else -> Unit
                    }
                }
            }
        }
        if (receipts.isNotEmpty()) dispatchedFocus = FocusDispatch(target)
        watchAll(receipts) { outcomes ->
            if (pending.epoch != epoch || pendingFocus !== pending) return@watchAll
            pendingFocus = null
            if (!rejected && outcomes.all { it == CommandOutcome.Acknowledged }) {
                focused = target
                possiblyFocused.clear()
                target?.let { possiblyFocused += it }
            }
            else {
                // A retry must not treat an uncertain focus transition as already delivered.
                focused = null
                dispatchedFocus = null
            }
        }
    }

    private fun releaseHeld() {
        releaseWanted += possiblyOwned
        pendingSizes.clear()
        pendingClaims.clear()
        acknowledged.clear()
        reconcileReleases()
    }

    private fun reconcileReleases() {
        val blocks = releaseWanted.filter { it !in pendingReleases }.toSet()
        if (blocks.isEmpty()) return
        when (val submission = submit(Command.Control.Release(session, blocks))) {
            is CommandSubmission.Rejected -> {
                if (submission.reason == AdmissionFailure.Capacity) waitingForCapacity = true
            }
            is CommandSubmission.Accepted -> {
                val token = Any()
                val requestEpoch = epoch
                blocks.forEach { pendingReleases[it] = token }
                watch(submission.receipt) { outcome ->
                    for (block in blocks) {
                        if (requestEpoch != epoch || pendingReleases[block] !== token) continue
                        pendingReleases.remove(block)
                        if (outcome == CommandOutcome.Acknowledged) {
                            releaseWanted.remove(block)
                            possiblyOwned.remove(block)
                            forget(block)
                        }
                    }
                }
            }
        }
    }

    private fun forget(block: BlockId) {
        claims.remove(block)
        pendingSizes.remove(block)
        pendingClaims.remove(block)
        acknowledged.remove(block)
    }

    private fun submit(command: Command.Control): CommandSubmission {
        val observed = host ?: return CommandSubmission.Rejected(AdmissionFailure.Disconnected)
        return calls.submit(observed.connection, command)
    }

    private fun serverFocused(block: BlockId): BlockId? {
        val state = host?.state ?: return null
        return state.sessionsById[session]?.windows.orEmpty().firstNotNullOfOrNull { id ->
            state.windowsById[id]?.takeIf { block in it.allBlocks() }?.focused
        }
    }

    private fun watch(receipt: Deferred<CommandOutcome>, done: (CommandOutcome) -> Unit) =
        watchAll(listOf(receipt)) { done(it.single()) }

    private fun watchAll(receipts: List<Deferred<CommandOutcome>>, done: (List<CommandOutcome>) -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcomes = receipts.map { it.await() }
            if (!closed) done(outcomes)
        }
        watchers += job
        job.invokeOnCompletion { watchers -= job }
        job.start()
    }
}
