package dev.mahali.hatch.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedInputTest {
    @Test fun `capacity version records completed roundtrips even when load ends unchanged`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val queue = OrderedInput(scope, {}, maxBatches = 1)
        val initialLoad = queue.load.value
        assertEquals(0L, queue.capacityVersion.value)
        repeat(3) { i ->
            assertTrue(queue.submit(1, emptySequence()))
            assertEquals(initialLoad, queue.load.value)
            assertEquals(i + 1L, queue.capacityVersion.value)
        }
        assertEquals(InputReservation.Completed(Unit), queue.exclusive { Unit }.await())
        assertEquals(initialLoad, queue.load.value)
        assertEquals(4L, queue.capacityVersion.value)
    }

    @Test fun `capacity version increments only on final accepted release not rejection`() = runTest {
        val replies = List(2) { CompletableDeferred<Unit>() }
        val queue = OrderedInput(backgroundScope, {}, maxBatches = 1)
        assertTrue(queue.submit(10, replies.asSequence().map { reply -> suspend { reply.await() } }))
        assertFalse(queue.submit(0, emptySequence()))
        assertEquals(InputReservation.Rejected, queue.exclusive { Unit }.await())
        assertEquals(0L, queue.capacityVersion.value)
        runCurrent()
        replies[0].complete(Unit)
        runCurrent()
        assertEquals(0L, queue.capacityVersion.value)
        replies[1].complete(Unit)
        runCurrent()
        assertEquals(InputLoad(), queue.load.value)
        assertEquals(1L, queue.capacityVersion.value)
        queue.close()
        assertFalse(queue.submit(0, emptySequence()))
        assertEquals(InputReservation.Rejected, queue.exclusive { Unit }.await())
        assertEquals(1L, queue.capacityVersion.value)
    }

    @Test fun `scope cancellation before worker starts discards pending receipts`() = runTest {
        val job = Job()
        val results = mutableListOf<InputCompletion>()
        val queue = OrderedInput(CoroutineScope(coroutineContext + job), {})
        assertTrue(queue.submit(1, emptySequence(), completed = { results += it }))
        job.cancel()
        runCurrent()
        assertEquals(listOf(InputCompletion.Discarded), results)
        assertEquals(InputLoad(), queue.load.value)
    }

    @Test fun `completion waits for every reply and reports failed calls`() = runTest {
        val reply = CompletableDeferred<Unit>()
        val results = mutableListOf<InputCompletion>()
        val queue = OrderedInput(backgroundScope, {})
        assertTrue(queue.submit(1, sequenceOf({ reply.await() }, { error("refused") }), completed = { results += it }))
        runCurrent()
        assertTrue(results.isEmpty())
        reply.complete(Unit)
        runCurrent()
        assertEquals("refused", (results.single() as InputCompletion.Failed).cause.message)
        assertEquals(InputLoad(), queue.load.value)
    }

    @Test fun `close distinguishes dispatched cancellation from undelivered discard`() = runTest {
        val results = mutableMapOf<Int, InputCompletion>()
        val queue = OrderedInput(backgroundScope, {}, maxInFlight = 1)
        repeat(3) { i ->
            assertTrue(queue.submit(10, sequenceOf({ CompletableDeferred<Unit>().await() }), completed = { results[i] = it }))
        }
        runCurrent()
        queue.close()
        runCurrent()
        assertEquals(InputCompletion.Cancelled, results[0])
        assertEquals(InputCompletion.Cancelled, results[1])
        assertEquals(InputCompletion.Discarded, results[2])
        assertEquals(InputLoad(), queue.load.value)
    }

    @Test fun `close before worker starts settles every pending batch once`() = runTest {
        val results = mutableListOf<InputCompletion>()
        val queue = OrderedInput(backgroundScope, {})
        repeat(3) { assertTrue(queue.submit(1, emptySequence(), completed = { results += it })) }
        queue.close()
        runCurrent()
        assertEquals(List(3) { InputCompletion.Discarded }, results)
        assertEquals(InputLoad(), queue.load.value)
    }

    @Test fun `full batch and byte budgets reject whole input without inspecting it`() = runTest {
        val reply = CompletableDeferred<Unit>()
        val queue = OrderedInput(backgroundScope, {}, maxBatches = 2, maxInFlight = 1, maxBytes = 100)
        val call: suspend () -> Unit = { reply.await() }
        assertTrue(queue.submit(60, sequenceOf(call)))
        assertFalse(queue.submit(41, sequence { error("Rejected text must not be expanded") }))
        assertTrue(queue.submit(40, sequenceOf(call)))
        assertFalse(queue.submit(0, sequenceOf(call)))
        runCurrent()
        assertEquals(InputLoad(2, 1, 100), queue.load.value)
        reply.complete(Unit)
        runCurrent()
        assertEquals(InputLoad(), queue.load.value)
        assertTrue(queue.submit(100, emptySequence()))
        runCurrent()
        assertEquals(InputLoad(), queue.load.value)
    }

    @Test fun `request failure is reported once and never replayed`() = runTest {
        val failures = mutableListOf<Exception>()
        var calls = 0
        val queue = OrderedInput(backgroundScope, { failures += it })
        assertTrue(queue.submit(1, sequenceOf({ calls++; error("disconnected after send") })))
        runCurrent()
        assertEquals(1, calls)
        assertEquals("disconnected after send", failures.single().message)
        assertEquals(InputLoad(), queue.load.value)
    }

}
