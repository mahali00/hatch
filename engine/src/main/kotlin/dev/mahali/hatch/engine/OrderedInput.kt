package dev.mahali.hatch.engine

import java.util.concurrent.atomic.AtomicInteger
import dev.mahali.hatch.core.BlockId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

public const val INPUT_MAX_IN_FLIGHT: Int = 32

public data class InputLoad(val batches: Int = 0, val inFlight: Int = 0, val bytes: Long = 0)

internal data class InputRevision(val pane: Long, val shared: Long)
internal sealed interface InputCompletion {
    data object Completed : InputCompletion
    data object Discarded : InputCompletion
    data object Cancelled : InputCompletion
    data class Failed(val cause: Exception) : InputCompletion
}
internal sealed interface InputReservation<out T> {
    data object Rejected : InputReservation<Nothing>
    data class Completed<T>(val value: T) : InputReservation<T>
    data class Failed(val cause: Exception) : InputReservation<Nothing>
}

internal class OrderedInput(
    scope: CoroutineScope,
    private val failure: (Exception) -> Unit,
    private val maxBatches: Int = 256,
    private val maxInFlight: Int = INPUT_MAX_IN_FLIGHT,
    private val maxBytes: Long = 8 * 1024 * 1024,
) {
    private val lock = Any()
    private val loadFlow = MutableStateFlow(InputLoad())
    val load: StateFlow<InputLoad> get() = loadFlow
    private val capacityVersionFlow = MutableStateFlow(0L)
    /** Bumps on each accepted batch's final release, so equal loads still differ. */
    val capacityVersion: StateFlow<Long> get() = capacityVersionFlow
    private val revisions = mutableMapOf<BlockId, Long>()
    private var sharedRevision = 0L

    fun revision(block: BlockId): InputRevision = synchronized(lock) {
        InputRevision(revisions[block] ?: 0L, sharedRevision)
    }

    internal inner class Permit(private val active: () -> Boolean) {
        fun claim(block: BlockId, expected: InputRevision, dispatch: () -> Unit): Boolean = synchronized(lock) {
            if (!active() || revision(block) != expected) false else { dispatch(); true }
        }
    }

    private inner class Batch(
        val bytes: Long,
        val calls: Sequence<suspend () -> Unit>,
        val exclusive: (suspend () -> Unit)? = null,
        val discarded: () -> Unit = {},
        val completed: (InputCompletion) -> Unit = {},
    ) {
        val references = AtomicInteger(1)
        private var outcome: InputCompletion = InputCompletion.Completed
        fun finish(result: InputCompletion) = synchronized(lock) {
            if (outcome == InputCompletion.Completed) outcome = result
        }
        fun release(notify: Boolean = true) {
            if (references.decrementAndGet() == 0) {
                val result = synchronized(lock) {
                    loadFlow.value = loadFlow.value.let { it.copy(batches = it.batches - 1, bytes = it.bytes - bytes) }
                    if (notify) capacityVersionFlow.value += 1L
                    outcome
                }
                if (notify) completed(result)
            }
        }
    }

    private val queue = Channel<Batch>(maxBatches, onUndeliveredElement = {
        it.finish(InputCompletion.Discarded); it.discarded(); it.release()
    })
    private val worker = scope.launch {
        val slots = Semaphore(maxInFlight)
        try {
            for (batch in queue) {
                try {
                    val exclusive = batch.exclusive
                    if (exclusive != null) {
                        var acquired = 0
                        try {
                            repeat(maxInFlight) { slots.acquire(); acquired++ }
                            exclusive()
                        } finally {
                            repeat(acquired) { slots.release() }
                            batch.discarded()
                        }
                        continue
                    }
                    for (call in batch.calls) {
                        slots.acquire()
                        batch.references.incrementAndGet()
                        synchronized(lock) { loadFlow.value = loadFlow.value.let { it.copy(inFlight = it.inFlight + 1) } }
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                call()
                            } catch (e: CancellationException) {
                                batch.finish(InputCompletion.Cancelled)
                                throw e
                            } catch (e: Exception) {
                                batch.finish(InputCompletion.Failed(e))
                                failure(e)
                            } finally {
                                synchronized(lock) { loadFlow.value = loadFlow.value.let { it.copy(inFlight = it.inFlight - 1) } }
                                batch.release()
                                slots.release()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    batch.finish(InputCompletion.Cancelled)
                    throw e
                } catch (e: Exception) {
                    batch.finish(InputCompletion.Failed(e))
                    failure(e)
                } finally {
                    batch.release()
                }
            }
        } finally {
            queue.cancel()
        }
    }

    init {
        // A cancelled, never-started coroutine does not enter the worker's finally block.
        worker.invokeOnCompletion { queue.cancel() }
    }

    /** Accepts a whole batch or none of it; never blocks. */
    fun submit(
        bytes: Long,
        calls: Sequence<suspend () -> Unit>,
        block: BlockId? = null,
        completed: (InputCompletion) -> Unit = {},
    ): Boolean = synchronized(lock) {
        val before = loadFlow.value
        if (!worker.isActive || before.batches >= maxBatches || bytes > maxBytes - before.bytes) return false
        loadFlow.value = before.copy(batches = before.batches + 1, bytes = before.bytes + bytes)
        val batch = Batch(bytes, calls, completed = completed)
        if (queue.trySend(batch).isSuccess) {
            if (block == null) sharedRevision++ else revisions[block] = (revisions[block] ?: 0L) + 1
            true
        } else { batch.release(notify = false); false }
    }

    fun <T> exclusive(operation: suspend (Permit) -> T): Deferred<InputReservation<T>> = synchronized(lock) {
        val answer = CompletableDeferred<InputReservation<T>>()
        val before = loadFlow.value
        val bytes = 256L
        if (!worker.isActive || before.batches >= maxBatches || bytes > maxBytes - before.bytes) {
            answer.complete(InputReservation.Rejected)
            return@synchronized answer
        }
        loadFlow.value = before.copy(batches = before.batches + 1, bytes = before.bytes + bytes)
        val batch = Batch(bytes, emptySequence(), exclusive = {
            if (answer.isActive) {
                try {
                    answer.complete(InputReservation.Completed(operation(Permit { answer.isActive })))
                } catch (error: Exception) {
                    answer.complete(InputReservation.Failed(error))
                    currentCoroutineContext().ensureActive()
                }
            }
        }, discarded = { answer.complete(InputReservation.Rejected) })
        if (!queue.trySend(batch).isSuccess) { batch.release(notify = false); answer.complete(InputReservation.Rejected) }
        answer
    }

    fun close() { worker.cancel(); queue.cancel() }

    val isClosed: Boolean get() = !worker.isActive
}
