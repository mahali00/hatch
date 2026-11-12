package dev.mahali.hatch.engine

import dev.mahali.hatch.core.Block
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaneLeaseTest {
    private val host = HostId("h")
    private val session = SessionId("s")
    private val tab = WindowId("w")
    private val a = BlockId("a")
    private val b = BlockId("b")
    private val grid = GridSize(80, 24, 10, 20)
    private val wider = GridSize(100, 24, 10, 20)
    private val colors = TerminalColors(0xFFFFFF, 0, 0xFFFFFF, List(16) { it }, dark = true)
    private val light = colors.copy(background = 0xFFFFFF, dark = false)

    private fun hostState(link: Link = Link.Online, owners: Map<BlockId, String?> = emptyMap()) = HostState(
        Host(host, "Mac", "https://example.invalid"), link = link, clientId = "me", sessions = listOf(session),
        sessionsById = mapOf(session to Session(session, "Work", windows = listOf(tab))),
        windowsById = mapOf(tab to Window(tab, "Tab", Layout.Split(dev.mahali.hatch.core.Axis.Row, 0.5f, Layout.Leaf(a), Layout.Leaf(b)), focused = a)),
        blocksById = mapOf(a to Block(a, owner = owners[a]), b to Block(b, owner = owners[b])),
    )

    private val sent = mutableListOf<String>()
    private val state = MutableStateFlow(LeaseHost(hostState()))
    private val calls = LeaseCalls(
        state,
        submit = { _, command ->
            sent += when (command) {
                is Command.Control.Claim -> "claim ${command.sizes.keys.joinToString { it.value }}"
                is Command.Control.Resize -> "resize ${command.sizes.keys.joinToString { it.value }}${if (command.colors != null) " +theme" else ""}"
                is Command.Control.Release -> "release ${command.blocks.joinToString { it.value }}"
                is Command.Control.Focus -> "focus ${command.block.value} ${if (command.focused) "in" else "out"}"
                is Command.Control.Select -> "select ${command.block.value}"
            }
            CommandSubmission.Accepted(CompletableDeferred(CommandOutcome.Acknowledged))
        },
    )

    private fun TestScope.lease() = PaneLease(calls, session, backgroundScope)
    private fun update(hostState: HostState) { state.value = state.value.copy(state = hostState) }

    @Test fun `size changes go in one batch, colours only when they changed`() = runTest(UnconfinedTestDispatcher()) {
        val lease = lease()
        lease.want(listOf(a, b), mapOf(a to grid, b to grid), colors)
        sent.clear()
        lease.want(listOf(a, b), mapOf(a to wider, b to wider), colors)
        lease.want(listOf(a, b), mapOf(a to wider, b to wider), light)
        assertEquals(listOf("resize a, b", "resize a, b +theme"), sent)
    }

    @Test fun `a pane moved away from is given back, but nothing is released mid-swipe`() = runTest(UnconfinedTestDispatcher()) {
        val lease = lease()
        lease.want(listOf(a, b), mapOf(a to grid, b to grid), colors)
        sent.clear()
        lease.want(emptyList(), emptyMap(), colors)
        assertTrue(sent.isEmpty())
        lease.want(listOf(b), mapOf(b to grid), colors)
        assertEquals(listOf("release a"), sent)
    }

    @Test fun `a hold the server dropped is claimed again`() = runTest(UnconfinedTestDispatcher()) {
        val lease = lease()
        lease.want(listOf(a), mapOf(a to grid), colors)
        update(hostState(owners = mapOf(a to "me")))
        sent.clear()
        update(hostState(owners = mapOf(a to null)))
        assertEquals(listOf("claim a"), sent)
    }

    @Test fun `a pane another device took waits for the user to touch it`() = runTest(UnconfinedTestDispatcher()) {
        val lease = lease()
        lease.want(listOf(a), mapOf(a to grid), colors)
        update(hostState(owners = mapOf(a to "me")))
        update(hostState(owners = mapOf(a to "mac")))
        lease.want(listOf(a), mapOf(a to wider), colors)
        sent.clear()
        lease.touched(a)
        assertEquals(listOf("claim a"), sent)
    }

    @Test fun `after a reconnect every visible pane is claimed and focused again`() = runTest(UnconfinedTestDispatcher()) {
        val lease = lease()
        lease.want(listOf(a), mapOf(a to grid), colors)
        lease.focus(a)
        update(hostState(link = Link.Offline("gone")))
        sent.clear()
        update(hostState(link = Link.Online))
        assertEquals(listOf("claim a", "focus a in"), sent)
    }

    @Test fun `a split the server already focused sends nothing`() = runTest(UnconfinedTestDispatcher()) {
        val lease = lease()
        lease.focusedByServer(b)
        lease.focus(b)
        assertTrue(sent.isEmpty())
    }

}
