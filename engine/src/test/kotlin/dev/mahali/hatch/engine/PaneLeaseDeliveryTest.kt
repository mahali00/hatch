package dev.mahali.hatch.engine

import dev.mahali.hatch.core.Block
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostFailure
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaneLeaseDeliveryTest {
    private val a = BlockId("a")
    private val b = BlockId("b")
    private val session = SessionId("session")
    private val window = WindowId("window")
    private val grid = GridSize(80, 24, 10, 20)
    private val wide = grid.copy(cols = 100)
    private val colors = TerminalColors(0xFFFFFF, 0, 0xFFFFFF, List(16) { it }, dark = true)

    private fun snapshot() = LeaseHost(
        HostState(
            Host(HostId("host"), "Test", "https://example.invalid"), link = Link.Online, clientId = "me",
            sessionsById = mapOf(session to Session(session, "", windows = listOf(window))),
            windowsById = mapOf(window to Window(window, "", Layout.Leaf(a), focused = a)),
            blocksById = mapOf(a to Block(a), b to Block(b)),
        ), connection = 1, generation = 1,
    )

    private fun acknowledged() = CommandSubmission.Accepted(
        CompletableDeferred<CommandOutcome>(CommandOutcome.Acknowledged),
    )

    private inner class Fixture(scope: CoroutineScope) {
        val host = MutableStateFlow(snapshot())
        val commands = mutableListOf<Command.Control>()
        var submit: (Command.Control) -> CommandSubmission = { acknowledged() }
        var transferCleanup: ((List<Command.Control>) -> Unit)? = null
        private val dispatch: (Command.Control) -> CommandSubmission = { command ->
            commands += command
            submit(command)
        }
        val lease = PaneLease(LeaseCalls(host, submit = { _, command -> dispatch(command) }, cleanup = { _, controls ->
            val transfer = transferCleanup
            if (transfer != null) transfer(controls) else controls.forEach { dispatch(it) }
        }), session, scope)

        fun want(size: GridSize = grid) = lease.want(listOf(a), mapOf(a to size), colors)
    }

    @Test fun `capacity recovery retries desired controls without polling`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.host.value = f.host.value.copy(load = InputLoad(batches = 2, bytes = 100))
        f.submit = { CommandSubmission.Rejected(AdmissionFailure.Capacity) }
        f.want()
        f.submit = { acknowledged() }
        f.host.value = f.host.value.copy(load = InputLoad())

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Claim>().size)
        f.host.value = f.host.value.copy(load = InputLoad(batches = 1))
        f.host.value = f.host.value.copy(load = InputLoad())
        assertEquals(2, f.commands.size)
    }

    @Test fun `a failed resize can be requested again at the same size`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.want()
        val reply = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(reply) }
        f.want(wide)
        reply.complete(CommandOutcome.Failed(HostFailure.Timeout, uncertain = true))
        f.submit = { acknowledged() }
        f.want(wide)
        f.want(wide)

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Resize>().size)
    }

    @Test fun `a capacity version wakes the lease even when intermediate load was conflated`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.submit = { CommandSubmission.Rejected(AdmissionFailure.Capacity) }
        f.want()
        f.submit = { acknowledged() }
        f.host.value = f.host.value.copy(capacityVersion = 1)

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Claim>().size)
    }

    @Test fun `a newer size acknowledgement does not conceal failed ownership`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        val claim = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(claim) }
        f.want()
        f.submit = { acknowledged() }
        f.want(wide)
        claim.complete(CommandOutcome.Failed(HostFailure.Rejected("busy", "Try later"), uncertain = false))
        f.want(wide)

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Claim>().size)
    }

    @Test fun `a late resize failure cannot invalidate a newer acknowledgement`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.want()
        val older = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(older) }
        f.want(wide)
        f.submit = { acknowledged() }
        f.want(grid.copy(cols = 120))
        older.complete(CommandOutcome.Failed(HostFailure.Timeout, uncertain = true))
        f.want(grid.copy(cols = 120))

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Resize>().size)
    }

    @Test fun `old generation replies cannot overwrite the new connection`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        val old = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(old) }
        f.want()
        f.submit = { acknowledged() }
        f.host.value = f.host.value.copy(generation = 2)
        old.complete(CommandOutcome.Failed(HostFailure.Disconnected, uncertain = true))
        f.want()

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Claim>().size)
    }

    @Test fun `replacing a host connection invalidates replies even with the same generation`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        val old = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(old) }
        f.want()
        f.submit = { acknowledged() }
        f.host.value = f.host.value.copy(connection = 2)
        old.complete(CommandOutcome.Acknowledged)
        f.want()

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Claim>().size)
    }

    @Test fun `closing revokes late replies and further intent`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        val old = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(old) }
        f.want()
        f.lease.close()
        val closedCount = f.commands.size
        old.complete(CommandOutcome.Acknowledged)
        f.host.value = f.host.value.copy(generation = 2)
        f.want(wide)
        f.lease.focus(a)
        f.lease.close()

        assertEquals(closedCount, f.commands.size)
        assertEquals(1, f.commands.filterIsInstance<Command.Control.Release>().size)
    }

    @Test fun `background capacity recovery releases without reclaiming hidden panes`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.want()
        f.host.value = f.host.value.copy(load = InputLoad(batches = 2))
        f.submit = { CommandSubmission.Rejected(AdmissionFailure.Capacity) }
        f.lease.background()
        f.submit = { acknowledged() }
        f.host.value = f.host.value.copy(load = InputLoad())

        assertEquals(1, f.commands.filterIsInstance<Command.Control.Claim>().size)
        assertEquals(2, f.commands.filterIsInstance<Command.Control.Release>().size)
    }

    @Test fun `a returning pane claims after an admitted release and ignores its old reply`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.want()
        val release = CompletableDeferred<CommandOutcome>()
        f.submit = { command -> if (command is Command.Control.Release) CommandSubmission.Accepted(release) else acknowledged() }
        f.lease.background()
        f.want()
        release.complete(CommandOutcome.Acknowledged)
        f.want()

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Claim>().size)
    }

    @Test fun `rejected focus is retried only on renewed intent`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.submit = { CommandSubmission.Rejected(AdmissionFailure.Capacity) }
        f.lease.focus(b)
        f.submit = { acknowledged() }
        f.lease.focus(b)
        f.lease.focus(b)

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Select>().size)
    }

    @Test fun `closing releases every possibly focused pane after partial admission`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.lease.focus(a)
        val state = f.host.value.state!!
        f.host.value = f.host.value.copy(state = state.copy(
            windowsById = state.windowsById + (window to state.windowsById.getValue(window).copy(
                focused = b,
                layout = Layout.Split(dev.mahali.hatch.core.Axis.Row, 0.5f, Layout.Leaf(a), Layout.Leaf(b)),
            )),
        ))
        f.submit = { command ->
            if (command is Command.Control.Focus && command.block == a && !command.focused) {
                CommandSubmission.Rejected(AdmissionFailure.Capacity)
            } else acknowledged()
        }
        f.lease.focus(b)
        f.submit = { acknowledged() }
        f.lease.close()

        assertEquals(listOf(
            Command.Control.Focus(session, a, false),
            Command.Control.Focus(session, b, false),
        ), f.commands.takeLast(2))
    }

    @Test fun `returning before focus out acknowledges sends a new focus in`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        f.want()
        f.lease.focus(a)
        val out = CompletableDeferred<CommandOutcome>()
        f.submit = { command ->
            if (command is Command.Control.Focus && !command.focused) CommandSubmission.Accepted(out)
            else acknowledged()
        }
        f.lease.background()
        f.want()
        f.lease.focus(a)
        out.complete(CommandOutcome.Acknowledged)
        f.lease.focus(a)

        assertEquals(2, f.commands.filterIsInstance<Command.Control.Focus>().count { it.focused })
        assertEquals(Command.Control.Focus(session, a, true), f.commands.last())
    }

    @Test fun `a partially failed claim retains its cleanup obligation before owner observation`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        val claim = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(claim) }
        f.want()
        claim.complete(CommandOutcome.Failed(HostFailure.Rejected("theme_failed", "Sizing failed after ownership"), uncertain = false))
        f.submit = { acknowledged() }
        f.lease.close()

        assertEquals(Command.Control.Release(session, setOf(a)), f.commands.last())
    }

    @Test fun `uncertain claim cleanup transfers to the host even when admission is full`() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(backgroundScope)
        val claim = CompletableDeferred<CommandOutcome>()
        f.submit = { CommandSubmission.Accepted(claim) }
        f.want()
        claim.complete(CommandOutcome.Failed(HostFailure.Timeout, uncertain = true))
        f.submit = { CommandSubmission.Rejected(AdmissionFailure.Capacity) }
        val transferred = mutableListOf<Command.Control>()
        f.transferCleanup = { transferred += it }
        f.lease.close()
        f.lease.close()

        assertEquals(listOf(Command.Control.Release(session, setOf(a))), transferred)
    }
}
