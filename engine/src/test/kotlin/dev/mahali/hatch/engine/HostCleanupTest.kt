package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.protocol.RexError
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCleanupTest {
    private val session = SessionId("s")
    private val pane = BlockId("p")
    private val focusOut = Command.Control.Focus(session, pane, false)
    private val release = Command.Control.Release(session, setOf(pane))
    private val colors = TerminalColors(0xFFFFFF, 0, 0xFFFFFF, List(16) { it }, dark = true)
    private val claim = Command.Control.Claim(session, mapOf(pane to GridSize(80, 24, 10, 20)), colors)
    private val paste = Command.Input.Paste(session, pane, "earlier input")

    @Test fun `reentrant cleanup coalesces the admitting key and drains appended teardown`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val input = OrderedInput(scope, {})
        val admitted = mutableListOf<Command.Control>()
        lateinit var cleanup: HostCleanup
        cleanup = HostCleanup(input, null, { true }, { command ->
            admitted += command
            if (command == focusOut) cleanup.enqueue(listOf(focusOut, release))
            CommandSubmission.Accepted(CompletableDeferred(CommandOutcome.Acknowledged))
        })
        try {
            cleanup.enqueue(listOf(focusOut))
            assertEquals(listOf(focusOut, release), admitted)
            assertTrue(cleanup.drain())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `pending teardown survives capacity rejection and precedes a later claim`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Command>()
        val input = OrderedInput(scope, {}, maxBatches = 1)
        val channel = HostChannel("host", input, { true }, { command ->
            sequenceOf({ sent += command; if (command is Command.Input.Paste) gate.await() })
        }, {})
        try {
            assertTrue(channel.send(paste) is CommandSubmission.Accepted)
            repeat(100) { channel.cleanup(listOf(focusOut, release)) }
            assertEquals(CommandSubmission.Rejected(AdmissionFailure.Capacity), channel.send(claim))
            assertEquals(listOf(paste), sent)
            gate.complete(Unit)
            val submission = channel.send(claim) as CommandSubmission.Accepted
            assertEquals(CommandOutcome.Acknowledged, submission.receipt.await())
            assertEquals(listOf(paste, focusOut, release, claim), sent)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `host worker admits teardown after capacity returns without another lease call`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val sent = CopyOnWriteArrayList<Command>()
        val input = OrderedInput(scope, {}, maxBatches = 1)
        val channel = HostChannel("host", input, { true }, { command ->
            sequenceOf({
                sent += command
                if (command is Command.Input.Paste) gate.await()
                if (command is Command.Control.Release) released.complete(Unit)
            })
        }, {}, scope = scope)
        try {
            channel.send(paste)
            channel.cleanup(listOf(focusOut, release))
            assertEquals(listOf(paste), sent.toList())
            gate.complete(Unit)
            withTimeout(5000) { released.await() }
            assertEquals(listOf(paste, focusOut, release), sent.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `offline teardown coalesces and reconnect retry needs no capacity change`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val input = OrderedInput(scope, {})
        val sent = mutableListOf<Command>()
        var online = false
        val channel = HostChannel("host", input, { online }, { command -> sequenceOf({ sent += command }) }, {})
        try {
            repeat(100) { channel.cleanup(listOf(focusOut, release)) }
            assertTrue(sent.isEmpty())
            assertEquals(0L, input.capacityVersion.value)
            assertEquals(CommandSubmission.Rejected(AdmissionFailure.Disconnected), channel.send(claim))
            online = true
            channel.retryCleanup()
            assertEquals(listOf(focusOut, release), sent)
            channel.retryCleanup()
            assertEquals(listOf(focusOut, release), sent)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `cancelled host cannot revive retained teardown on reconnect`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val input = OrderedInput(scope, {})
        val sent = CopyOnWriteArrayList<Command>()
        var online = false
        val channel = HostChannel("host", input, { online }, { command -> sequenceOf({ sent += command }) }, {}, scope = scope)
        channel.cleanup(listOf(focusOut, release))
        scope.cancel()
        online = true
        channel.retryCleanup()
        channel.cleanup(listOf(focusOut, release))
        assertTrue(sent.isEmpty())
        assertEquals(CommandSubmission.Rejected(AdmissionFailure.Closed), channel.send(claim))
    }

    @Test fun `admitted cleanup RPC failure is logged but never replayed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val input = OrderedInput(scope, {})
        val sent = mutableListOf<Command>()
        val logs = mutableListOf<String>()
        val channel = HostChannel("host", input, { true }, { command ->
            sequenceOf({ sent += command; throw RexError("bad_request", "refused") })
        }, {}, { logs += it })
        try {
            channel.cleanup(listOf(focusOut, release))
            repeat(10) { channel.retryCleanup() }
            assertEquals(listOf(focusOut, release), sent)
            assertEquals(2, logs.size)
            assertTrue(logs.all { "refused" in it })
        } finally {
            scope.cancel()
        }
    }

}
