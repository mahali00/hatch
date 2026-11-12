package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Teardown outlives its lease but never its host connection; no input is retained here. */
internal class HostCleanup(
    private val input: OrderedInput,
    private val scope: CoroutineScope?,
    private val online: () -> Boolean,
    private val admit: (Command.Control) -> CommandSubmission,
) {
    private data class Key(val session: SessionId, val block: BlockId, val release: Boolean)

    private val lock = Any()
    private val pending = linkedMapOf<Key, Command.Control>()
    private var draining = false
    private var retryRequested = false

    init {
        // Never admit inline in a capacity notification: it runs under OrderedInput's lock.
        // The job stays in the host scope, even when that scope is unconfined.
        scope?.launch(Dispatchers.Default) {
            try {
                input.capacityVersion.collect { drain() }
            } finally {
                synchronized(lock) { pending.clear() }
            }
        }
    }

    fun enqueue(commands: List<Command.Control>) {
        require(commands.all { it is Command.Control.Release || it is Command.Control.Focus && !it.focused }) {
            "Host cleanup accepts only release and focus-out commands"
        }
        synchronized(lock) {
            if (!alive()) {
                pending.clear()
                return
            }
            for (command in commands) {
                when (command) {
                    is Command.Control.Release -> command.blocks.forEach { block ->
                        pending.putIfAbsent(Key(command.session, block, true), Command.Control.Release(command.session, setOf(block)))
                    }
                    is Command.Control.Focus -> pending.putIfAbsent(Key(command.session, command.block, false), command)
                    else -> error("Not a teardown command")
                }
            }
        }
        drain()
    }

    /** False means teardown is still pending, so newer commands must not overtake it. */
    fun drain(): Boolean {
        synchronized(lock) {
            if (!alive()) {
                pending.clear()
                return true
            }
            if (draining) {
                retryRequested = true
                return pending.isEmpty()
            }
            if (!online()) return pending.isEmpty()
            draining = true
        }
        try {
            while (true) {
                val next = synchronized(lock) {
                    if (!alive()) pending.clear()
                    val first = pending.entries.firstOrNull()
                    if (first == null) {
                        draining = false
                        return true
                    }
                    if (!online()) {
                        draining = false
                        return false
                    }
                    retryRequested = false
                    first.key to first.value
                }
                // Neither our lock nor HostChannel's lock is held across queue admission.
                val submission = admit(next.second)
                synchronized(lock) {
                    when (submission) {
                        is CommandSubmission.Accepted -> pending.remove(next.first)
                        is CommandSubmission.Rejected -> {
                            if (submission.reason == AdmissionFailure.Closed) pending.clear()
                            // A capacity notification can arrive while another thread is trying
                            // admission. Consume that wakeup rather than losing it on rejection.
                            if (pending.isNotEmpty() && retryRequested && alive() && online()) return@synchronized
                            draining = false
                            return pending.isEmpty()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(lock) { draining = false }
            throw error
        }
    }

    private fun alive(): Boolean = !input.isClosed && scope?.coroutineContext?.get(Job)?.isActive != false
}
