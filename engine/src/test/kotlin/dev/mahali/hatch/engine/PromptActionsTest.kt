package dev.mahali.hatch.engine

import dev.mahali.hatch.core.AnswerOutcome
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Block
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.PromptFallback
import dev.mahali.hatch.core.PromptState
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.StatusRecord
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.protocol.ForegroundProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptActionsTest {
    private val host = HostId("host")
    private val session = SessionId("session")
    private val window = WindowId("window")
    private val block = BlockId("block")
    private val target = PromptTarget(WindowRef(host, session, window), block)
    private fun initial(): AppState {
        val state = HostState(Host(host, "Host", "https://example.invalid"), link = Link.Online, serverId = "server",
            sessions = listOf(session), sessionsById = mapOf(session to Session(session, "Test", windows = listOf(window))),
            windowsById = mapOf(window to Window(window, "Test", Layout.Leaf(block))),
            blocksById = mapOf(block to Block(block, status = mapOf("" to StatusRecord("", ProgramState.Blocked)))))
        return AppState(listOf(host), mapOf(host to state))
    }
    private inner class Runtime : PromptRuntime {
        override val state = MutableStateFlow(initial())
        override val hints = MutableSharedFlow<PromptTarget>(extraBufferCapacity = 4)
        var sourceValue: PromptSource? = PromptSource(this, 1, "server", InputRevision(0, 0))
        val prompt = ParsedPrompt(PromptAgent.Codex, "Run command?", "printf hello", listOf(ParsedChoice("Yes", 1), ParsedChoice("No", 2)))
        var reads = 0
        var activeReads = 0
        var peakReads = 0
        var readGate: CompletableDeferred<Unit>? = null
        var answers = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        override fun source(target: PromptTarget): PromptSource? = sourceValue
        override suspend fun read(target: PromptTarget): PromptObservation {
            reads++
            activeReads++
            peakReads = maxOf(peakReads, activeReads)
            try {
                readGate?.await()
                failure?.let { throw it }
                return PromptObservation(sourceValue!!, ForegroundProcess(1, "codex", "/bin/codex", "", "", "codex"), prompt)
            } finally { activeReads-- }
        }
        override suspend fun answer(target: PromptTarget, observation: PromptObservation, number: Int, valid: () -> Boolean, sending: () -> Unit): AnswerOutcome {
            answers++
            sending()
            gate?.await()
            return if (valid()) AnswerOutcome.InputAcknowledged else AnswerOutcome.NotSent(PromptFallback.Changed)
        }
    }

    @Test fun `bootstrap discovers blocked prompts without a signal and expires without polling`() = runTest {
        val runtime = Runtime()
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        assertTrue(prompts.entries.value[target] is PromptState.Offered)
        assertEquals(1, runtime.reads)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(PromptState.OpenOnly(PromptFallback.Expired), prompts.entries.value[target])
        assertEquals(1, runtime.reads)
    }

    @Test fun `sheet and notification cannot answer one observation twice`() = runTest {
        val runtime = Runtime().also { it.gate = CompletableDeferred() }
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        val view = (prompts.entries.value.getValue(target) as PromptState.Offered).view
        val first = async { prompts.answer(view.choices.first.action) }
        runCurrent()
        assertEquals(AnswerOutcome.NotSent(PromptFallback.AlreadyAnswered), prompts.answer(view.choices.rest.first().action))
        assertEquals(1, runtime.answers)
        runtime.gate!!.complete(Unit)
        runCurrent()
        assertEquals(AnswerOutcome.InputAcknowledged, first.await())
    }

    @Test fun `reconnected source rejects an old action before dispatch`() = runTest {
        val runtime = Runtime()
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        val action = (prompts.entries.value.getValue(target) as PromptState.Offered).view.choices.first.action
        runtime.sourceValue = runtime.sourceValue!!.copy(generation = 2)
        assertTrue(prompts.answer(action) is AnswerOutcome.NotSent)
        assertEquals(0, runtime.answers)
    }

    @Test fun `repeated hints coalesce and attempted prompt gets no new actions`() = runTest {
        val runtime = Runtime()
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent()
        repeat(100) { prompts.inspect(target) }
        advanceTimeBy(151); runCurrent()
        assertEquals(1, runtime.reads)
        val action = (prompts.entries.value.getValue(target) as PromptState.Offered).view.choices.first.action
        prompts.answer(action)
        advanceTimeBy(2_500); runCurrent()
        assertTrue(prompts.entries.value[target] is PromptState.OpenOnly)
        assertEquals(1, runtime.answers)
    }

    @Test fun `pane removal invalidates an answer which is already being checked`() = runTest {
        val runtime = Runtime().also { it.gate = CompletableDeferred() }
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        val action = (prompts.entries.value.getValue(target) as PromptState.Offered).view.choices.first.action
        val answer = async { prompts.answer(action) }
        runCurrent()
        runtime.state.value = AppState()
        runtime.sourceValue = null
        runCurrent()
        runtime.gate!!.complete(Unit)
        runCurrent()
        assertEquals(AnswerOutcome.NotSent(PromptFallback.Changed), answer.await())
        assertTrue(prompts.entries.value.isEmpty())
    }

    @Test fun `disabled watcher reads nothing and enabling seeds existing blocked prompts`() = runTest {
        val runtime = Runtime()
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler), initiallyEnabled = false)
        runCurrent()
        runtime.hints.emit(target)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, runtime.reads)
        prompts.setEnabled(true)
        advanceTimeBy(151); runCurrent()
        val action = (prompts.entries.value.getValue(target) as PromptState.Offered).view.choices.first.action
        prompts.setEnabled(false)
        assertTrue(prompts.entries.value.isEmpty())
        assertTrue(prompts.answer(action) is AnswerOutcome.NotSent)
        assertEquals(0, runtime.answers)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, runtime.reads)
    }

    @Test fun `disabling while checking revokes the captured answer claim`() = runTest {
        val runtime = Runtime().also { it.gate = CompletableDeferred() }
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        val action = (prompts.entries.value.getValue(target) as PromptState.Offered).view.choices.first.action
        val answer = async { prompts.answer(action) }
        runCurrent()
        prompts.setEnabled(false)
        runtime.gate!!.complete(Unit)
        runCurrent()
        assertEquals(AnswerOutcome.NotSent(PromptFallback.Changed), answer.await())
        assertTrue(prompts.entries.value.isEmpty())
    }

    @Test fun `capture failure keeps an open-only entry rather than retrying forever`() = runTest {
        val runtime = Runtime().also { it.failure = IllegalStateException("unsupported capture") }
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        assertEquals(PromptState.OpenOnly(PromptFallback.Unsupported), prompts.entries.value[target])
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, runtime.reads)
    }

    @Test fun `discovery across hosts never runs more than one inspection at once`() = runTest {
        val runtime = Runtime().also { it.readGate = CompletableDeferred() }
        val other = HostId("other")
        val first = runtime.state.value.byHost.getValue(host)
        runtime.state.value = AppState(listOf(host, other), mapOf(host to first, other to first.copy(host = first.host.copy(id = other))))
        PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        assertEquals(1, runtime.reads)
        runtime.readGate!!.complete(Unit)
        runCurrent()
        assertEquals(2, runtime.reads)
        assertEquals(1, runtime.peakReads)
    }

    @Test fun `progress and timestamp reports do not repeat screen captures`() = runTest {
        val runtime = Runtime()
        PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        repeat(100) { index ->
            val previous = runtime.state.value.byHost.getValue(host)
            val pane = previous.blocksById.getValue(block)
            val status = pane.status.getValue("").copy(progress = index, updatedAt = "report-$index")
            runtime.state.value = runtime.state.value.copy(byHost = mapOf(host to previous.copy(
                blocksById = mapOf(block to pane.copy(status = mapOf("" to status))))))
            runCurrent()
        }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, runtime.reads)
    }

    @Test fun `cancelled answer cannot restore a notification for a removed pane`() = runTest {
        val runtime = Runtime().also { it.gate = CompletableDeferred() }
        val prompts = PromptActions(runtime, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(151); runCurrent()
        val action = (prompts.entries.value.getValue(target) as PromptState.Offered).view.choices.first.action
        val answer = async { prompts.answer(action) }
        runCurrent()
        runtime.state.value = AppState()
        runtime.sourceValue = null
        runCurrent()
        answer.cancelAndJoin()
        assertTrue(prompts.entries.value.isEmpty())
    }
}
