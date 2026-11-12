package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostFailure
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.protocol.RexApi
import dev.mahali.hatch.protocol.RexError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Ordered input and control for one host. Wire order is submission order. A waiting resize is
 * updated in place, so the latest size never lands after input typed later.
 */
internal class HostChannel(
    private val name: String,
    private val input: OrderedInput,
    private val online: () -> Boolean,
    private val perform: (Command) -> Sequence<suspend () -> Unit>,
    private val inputFailed: (String) -> Unit,
    private val log: (String) -> Unit = {},
    scope: CoroutineScope? = null,
) {
    private class WaitingResize(val session: SessionId, val sizes: LinkedHashMap<BlockId, Pair<GridSize, TerminalColors?>>) {
        val owners = mutableMapOf<BlockId, Delivery>()
    }

    private class Delivery {
        val receipt = CompletableDeferred<CommandOutcome>()
        val entries = mutableSetOf<WaitingResize>()
        var failure: CommandOutcome.Failed? = null
    }

    private val lock = Any()
    private val waiting = mutableListOf<WaitingResize>()
    private val teardown = HostCleanup(input, scope, online, ::submit)

    fun send(command: Command): CommandSubmission {
        if (input.isClosed || !online()) return submit(command)
        if (!teardown.drain()) {
            if (command is Command.Input) inputFailed("$name: input queue is full. This input wasn't sent; wait for the host before trying again.")
            else log("$name: pending host cleanup, dropped ${command::class.simpleName}")
            return CommandSubmission.Rejected(AdmissionFailure.Capacity)
        }
        return submit(command)
    }

    fun cleanup(commands: List<Command.Control>) {
        teardown.enqueue(commands)
    }

    fun retryCleanup() {
        teardown.drain()
    }

    private fun submit(command: Command): CommandSubmission {
        val typed = command is Command.Input
        if (input.isClosed) {
            if (typed) inputFailed("Input wasn't sent because the input channel is closed.")
            else log("$name: input channel closed, dropped ${command::class.simpleName}")
            return CommandSubmission.Rejected(AdmissionFailure.Closed)
        }
        if (!online()) {
            if (typed) inputFailed("Input wasn't sent because the host is disconnected.")
            return CommandSubmission.Rejected(AdmissionFailure.Disconnected)
        }
        val delivery = Delivery()
        val accepted = when (command) {
            is Command.Control.Resize -> resize(command, delivery)
            else -> {
                if (command is Command.Control.Claim || command is Command.Control.Release) {
                    // Ownership changed: a later resize must not be folded in ahead of it.
                    synchronized(lock) { waiting.removeAll { it.session == command.session } }
                }
                input.submit(bytes(command), perform(command).map { guarded(command, it, delivery) }, block(command)) {
                    synchronized(lock) { complete(delivery, it) }
                }
            }
        }
        if (!accepted) {
            if (typed) inputFailed("$name: input queue is full. This input wasn't sent; wait for the host before trying again.")
            else log("$name: queue full, dropped ${command::class.simpleName}")
        }
        return if (accepted) CommandSubmission.Accepted(delivery.receipt)
        else CommandSubmission.Rejected(if (input.isClosed) AdmissionFailure.Closed else AdmissionFailure.Capacity)
    }

    private fun resize(command: Command.Control.Resize, delivery: Delivery): Boolean = synchronized(lock) {
        val rest = LinkedHashMap<BlockId, Pair<GridSize, TerminalColors?>>()
        val folded = mutableMapOf<BlockId, WaitingResize>()
        for ((block, size) in command.sizes) {
            val earlier = waiting.firstOrNull { it.session == command.session && block in it.sizes }
            if (earlier == null) rest[block] = size to command.colors
            else folded[block] = earlier
        }
        val replaced = folded.mapNotNull { (block, entry) -> entry.owners[block] }.toSet()
        val previousSizes = folded.mapValues { (block, entry) -> entry.sizes.getValue(block) }
        val previousOwners = folded.mapValues { (block, entry) -> entry.owners[block] }
        // Install dependencies before submit: an unconfined worker can finish inside trySend.
        // Old receipts are superseded only once admission succeeds.
        folded.forEach { (block, entry) ->
            entry.sizes[block] = command.sizes.getValue(block) to (command.colors ?: entry.sizes.getValue(block).second)
            entry.owners[block] = delivery
            delivery.entries += entry
        }
        if (rest.isNotEmpty()) {
            val entry = WaitingResize(command.session, rest)
            waiting += entry
            delivery.entries += entry
            rest.keys.forEach { entry.owners[it] = delivery }
            val calls = sequence<suspend () -> Unit> {
                // The worker reached it: from here on its sizes are fixed.
                val sizes = synchronized(lock) { waiting -= entry; entry.sizes.toMap() }
                sizes.entries.groupBy({ it.value.second }, { it.key to it.value.first }).forEach { (colors, group) ->
                    val resize = Command.Control.Resize(command.session, group.toMap(), colors)
                    yieldAll(perform(resize).map { guardedResize(resize, it, entry) })
                }
            }
            if (!input.submit(0, calls, null) { result ->
                    synchronized(lock) {
                        waiting -= entry
                        entry.owners.values.toSet().forEach { owner ->
                            owner.entries -= entry
                            if (result != InputCompletion.Completed || owner.entries.isEmpty()) complete(owner, result)
                        }
                        entry.owners.clear()
                    }
                }) {
                waiting -= entry
                detach(delivery)
                folded.forEach { (block, earlier) ->
                    earlier.sizes[block] = previousSizes.getValue(block)
                    previousOwners[block]?.let { earlier.owners[block] = it }
                }
                return false
            }
        }
        replaced.forEach { supersede(it) }
        if (delivery.entries.isEmpty()) complete(delivery, InputCompletion.Completed)
        true
    }

    private fun supersede(delivery: Delivery) {
        delivery.receipt.complete(CommandOutcome.Superseded)
        detach(delivery)
    }

    private fun detach(delivery: Delivery) {
        delivery.entries.forEach { entry -> entry.owners.entries.removeAll { it.value === delivery } }
        delivery.entries.clear()
    }

    private fun complete(delivery: Delivery, result: InputCompletion) {
        val outcome = when (result) {
            InputCompletion.Cancelled, InputCompletion.Discarded -> CommandOutcome.Cancelled
            is InputCompletion.Failed -> failed(result.cause)
            InputCompletion.Completed -> delivery.failure ?: CommandOutcome.Acknowledged
        }
        delivery.receipt.complete(outcome)
        detach(delivery)
    }

    private fun failed(error: Exception): CommandOutcome.Failed {
        val failure = (error as? RexError)?.let { HostFailure.of(it.code, it.message.orEmpty()) }
            ?: HostFailure.Disconnected
        return CommandOutcome.Failed(failure, failure == HostFailure.Disconnected || failure == HostFailure.Timeout)
    }

    private fun guardedResize(command: Command.Control.Resize, call: suspend () -> Unit, entry: WaitingResize): suspend () -> Unit =
        guarded(command, call) { failure ->
            synchronized(lock) { command.sizes.keys.mapNotNull { entry.owners[it] }.toSet().forEach { record(it, failure) } }
        }

    private fun guarded(command: Command, call: suspend () -> Unit, delivery: Delivery): suspend () -> Unit =
        guarded(command, call) { failure -> synchronized(lock) { record(delivery, failure) } }

    private fun record(delivery: Delivery, failure: CommandOutcome.Failed) {
        val previous = delivery.failure
        delivery.failure = previous?.copy(uncertain = previous.uncertain || failure.uncertain) ?: failure
    }

    private fun guarded(command: Command, call: suspend () -> Unit, report: (CommandOutcome.Failed) -> Unit): suspend () -> Unit = {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(failed(e))
            val failure = (e as? RexError)?.let { HostFailure.of(it.code, it.message.orEmpty()) }
            when {
                failure == HostFailure.Gone -> log("$name: ${command::class.simpleName} to a closed pane: ${e.message}")
                command is Command.Input -> {
                    log("$name: input failed: ${e.message}")
                    inputFailed("$name: input delivery failed. Some input may not have reached the terminal.")
                }
                else -> log("$name: ${command::class.simpleName} failed: ${e.message}")
            }
        }
    }

    private fun bytes(command: Command): Long = when (command) {
        is Command.Input.Key -> 128L + command.key.text.length * 2L
        is Command.Input.Text -> 128L + command.text.length * 2L
        is Command.Input.Paste -> 128L + command.text.length * 2L
        is Command.Input.Wheel -> 128L + abs(command.lines.toLong()) * 16
        is Command.Input.Click -> 256L
        is Command.Control -> 0
    }

    private fun block(command: Command): BlockId? = when (command) {
        is Command.Input -> command.block
        is Command.Control.Focus -> command.block
        is Command.Control.Select -> command.block
        else -> null
    }
}

/** Each command as the calls that put it on the wire, one queue slot per call. */
internal fun RexApi.calls(command: Command): Sequence<suspend () -> Unit> = when (command) {
    is Command.Input.Key -> sequenceOf({ key(command.session, command.block, command.key) })
    is Command.Input.Text -> sequence {
        val text = command.text
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            val key = keyForText(String(Character.toChars(cp)), if (offset == 0) command.mods else Mods())
            yield(suspend { key(command.session, command.block, key) })
            offset += Character.charCount(cp)
        }
    }
    is Command.Input.Paste -> sequenceOf({ paste(command.session, command.block, command.text) })
    is Command.Input.Wheel -> {
        val payload = buildJsonObject {
            put("x", command.column)
            put("y", command.row)
            put("delta_y", if (command.lines < 0) -1 else 1)
            put("delta_mode", "line")
        }
        val count = abs(command.lines.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        generateSequence { suspend { blockEvent(command.session, command.block, "wheel", payload) } }.take(count)
    }
    is Command.Input.Click -> sequence {
        for (press in listOf(true, false)) {
            val payload = buildJsonObject {
                put("x", command.column)
                put("y", command.row)
                put("button", "left")
                put("action", if (press) "press" else "release")
                // Buttons held after the event; null means none, as in a release.
                if (press) put("buttons", buildJsonObject { put("left", true) }) else put("buttons", JsonNull)
            }
            yield(suspend { blockEvent(command.session, command.block, "mouse", payload) })
        }
    }
    is Command.Control.Claim -> sequenceOf({
        ownBlocks(command.session, command.sizes.keys.toList())
        sizeAndColor(command.session, command.sizes, command.colors)
    })
    is Command.Control.Resize -> sequenceOf({ sizeAndColor(command.session, command.sizes, command.colors) })
    is Command.Control.Release -> sequenceOf({ disownBlocks(command.session, command.blocks.toList()) })
    is Command.Control.Focus -> sequenceOf({ focus(command.session, command.block, command.focused) })
    is Command.Control.Select -> sequenceOf({ focusBlock(command.session, command.block) })
}

// One slot in the host's queue: UNDISPATCHED puts each resize on the socket before this suspends,
// so a key typed next cannot overtake it.
private suspend fun RexApi.sizeAndColor(session: SessionId, sizes: Map<BlockId, GridSize>, colors: TerminalColors?) = coroutineScope {
    sizes.forEach { (block, size) ->
        launch(start = CoroutineStart.UNDISPATCHED) {
            resize(session, block, size.cols, size.rows, size.cellWidth, size.cellHeight)
            if (colors != null) setTheme(session, block, colors.toJson())
        }
    }
}
