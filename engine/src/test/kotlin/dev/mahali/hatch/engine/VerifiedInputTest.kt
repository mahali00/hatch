package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedInputTest {
    @Test fun `reservation drains earlier replies and holds later input`() = runTest {
        val earlier = CompletableDeferred<Unit>()
        val verification = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val input = OrderedInput(backgroundScope, { throw AssertionError(it) }, maxInFlight = 2)
        input.submit(1, sequenceOf({ events += "earlier"; earlier.await() }))
        val result = input.exclusive { events += "verify"; verification.await(); events += "answer" }
        input.submit(1, sequenceOf({ events += "later" }))
        runCurrent()
        assertEquals(listOf("earlier"), events)
        earlier.complete(Unit)
        runCurrent()
        assertEquals(listOf("earlier", "verify"), events)
        verification.complete(Unit)
        runCurrent()
        assertEquals(listOf("earlier", "verify", "answer", "later"), events)
        assertTrue(result.await() is InputReservation.Completed)
        assertEquals(InputLoad(), input.load.value)
    }

    @Test fun `input admitted during verification invalidates the claim`() = runTest {
        val pane = BlockId("pane")
        val read = CompletableDeferred<Unit>()
        val input = OrderedInput(backgroundScope, {})
        val expected = input.revision(pane)
        var sent = false
        val result = input.exclusive { permit -> read.await(); permit.claim(pane, expected) { sent = true } }
        runCurrent()
        input.submit(1, emptySequence(), pane)
        read.complete(Unit)
        runCurrent()
        assertFalse(sent)
        assertEquals(InputReservation.Completed(false), result.await())
    }

    @Test fun `another pane does not invalidate a pane revision`() = runTest {
        val pane = BlockId("pane")
        val input = OrderedInput(backgroundScope, {})
        val expected = input.revision(pane)
        input.submit(1, emptySequence(), BlockId("other"))
        val result = input.exclusive { it.claim(pane, expected) {} }
        runCurrent()
        assertEquals(InputReservation.Completed(true), result.await())
    }

    @Test fun `cancelled queued reservation can never dispatch later`() = runTest {
        val earlier = CompletableDeferred<Unit>()
        val input = OrderedInput(backgroundScope, {}, maxInFlight = 1)
        input.submit(1, sequenceOf({ earlier.await() }))
        var ran = false
        val answer = input.exclusive { ran = true }
        runCurrent()
        answer.cancel()
        earlier.complete(Unit)
        runCurrent()
        assertFalse(ran)
        assertEquals(InputLoad(), input.load.value)
    }

    @Test fun `bounded admission rejects reservation without expanding input`() = runTest {
        val earlier = CompletableDeferred<Unit>()
        val input = OrderedInput(backgroundScope, {}, maxBatches = 1)
        input.submit(1, sequenceOf({ earlier.await() }))
        val answer = input.exclusive { error("Rejected operation ran") }
        assertEquals(InputReservation.Rejected, answer.await())
    }

    @Test fun `closing during drain releases the reservation and all memory`() = runTest {
        val input = OrderedInput(backgroundScope, {}, maxInFlight = 2)
        input.submit(1, sequenceOf({ CompletableDeferred<Unit>().await() }))
        val answer = input.exclusive { error("Closed operation ran") }
        runCurrent()
        input.close()
        runCurrent()
        assertEquals(InputReservation.Rejected, answer.await())
        assertEquals(InputLoad(), input.load.value)
    }
}
