package dev.mahali.hatch.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.PanePriority
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.blocks
import dev.mahali.hatch.protocol.ClientInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live check; mutations are confined to the temporary session it creates. */
@RunWith(AndroidJUnit4::class)
class LivePaneOwnershipTest {
    @Test fun leaseAcquisitionAndNativeGeometrySurviveBackgroundAndReconnect(): Unit = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("rex.liveUrl")
        assumeTrue("Pass rex.liveUrl to opt in to a live Rex server", !url.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val rex = Rex(scope, { ClientInfo(version = "ownership-test", label = "Hatch ownership QA") })
        val host = HostId("ownership-test")
        var session: SessionId? = null
        var lease: PaneLease? = null
        var acquisition: PaneAcquisition? = null
        var other: Rex? = null
        try {
            rex.setHosts(listOf(Host(host, "Ownership QA", url!!)))
            withTimeout(15_000) { rex.state.first { it.byHost[host]?.link == Link.Online } }
            val sync = checkNotNull(rex.sync(host))
            val api = sync.api
            val created = api.createSession("")
            session = created
            sync.refresh(created)
            api.setSessionLabel(created, "Temporary Hatch ownership QA")
            val initial = withTimeout(15_000) {
                sync.state.first { state ->
                    state.sessionsById[created]?.windows?.firstOrNull()
                        ?.let { state.windowsById[it]?.layout?.blocks()?.isNotEmpty() } == true
                }
            }
            val window = initial.sessionsById.getValue(created).windows.first()
            val block = initial.windowsById.getValue(window).layout!!.blocks().first()
            api.disownBlocks(created, listOf(block))
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.owner != it.clientId } }

            val colors = TerminalColors(0xFFFFFF, 0, 0xFFFFFF, List(16) { it }, dark = true)
            val initialGrid = GridSize(80, 24, 10, 20)
            val largerGrid = initialGrid.copy(cols = 100, rows = 30)
            withContext(Dispatchers.Main.immediate) {
                val acquired = checkNotNull(rex.acquire(host, created, block))
                acquisition = acquired
                acquired.attach({}, {}, {}).setPriority(PanePriority.Focused)
                lease = rex.lease(host, created, scope).also {
                    it.want(listOf(block), mapOf(block to initialGrid), colors)
                    it.focus(block)
                }
            }
            val terminal = checkNotNull(acquisition).pane
            withTimeout(15_000) {
                terminal.status.first { it.restored && it.stream == dev.mahali.hatch.protocol.DataStream.State.Live }
            }
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.owner == it.clientId } }
            withTimeout(10_000) { while (terminal.geometry.applied != initialGrid) delay(20) }

            withContext(Dispatchers.Main.immediate) {
                lease!!.want(listOf(block), mapOf(block to largerGrid), colors)
            }
            withTimeout(10_000) { while (terminal.geometry.applied != largerGrid) delay(20) }
            assertEquals(largerGrid, terminal.geometry.displayed)

            // Passive sizing must not steal the pane back; explicit focus may request it.
            val second = Rex(scope, { ClientInfo(version = "ownership-test", label = "Other ownership QA") })
            other = second
            second.setHosts(listOf(Host(host, "Ownership QA", url)))
            withTimeout(15_000) { second.state.first { it.byHost[host]?.link == Link.Online } }
            val secondSync = checkNotNull(second.sync(host))
            secondSync.refresh(created)
            secondSync.api.ownBlocks(created, listOf(block))
            val otherClient = secondSync.state.value.clientId
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.owner == otherClient } }
            withContext(Dispatchers.Main.immediate) {
                yield()
                lease!!.want(listOf(block), mapOf(block to largerGrid), colors)
            }
            withTimeout(10_000) { rex.inputLoad(host)!!.first { it.batches == 0 } }
            assertEquals(otherClient, sync.state.value.blocksById[block]?.owner)
            withContext(Dispatchers.Main.immediate) { lease!!.touched(block) }
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.owner == it.clientId } }

            withContext(Dispatchers.Main.immediate) { lease!!.background() }
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.owner != it.clientId } }
            withContext(Dispatchers.Main.immediate) {
                lease!!.want(listOf(block), mapOf(block to largerGrid), colors)
                lease!!.focus(block)
            }
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.owner == it.clientId } }

            val generation = sync.connectionGeneration.value
            rex.reconnect(host)
            withTimeout(15_000) { sync.connectionGeneration.first { it > generation } }
            withTimeout(15_000) {
                sync.state.first { it.link == Link.Online && it.blocksById[block]?.owner == it.clientId }
            }

            withContext(Dispatchers.Main.immediate) {
                lease!!.close()
                lease = null
                acquisition!!.close()
                assertFalse(acquisition!!.isActive)
            }
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.owner != it.clientId } }
        } finally {
            try {
                session?.let { created -> withTimeout(10_000) { rex.api(host)?.destroySession(created) } }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    lease?.close()
                    acquisition?.close()
                    other?.close()
                    rex.close()
                }
                scope.cancel()
                rex.http.dispatcher.executorService.shutdown()
                rex.http.connectionPool.evictAll()
                other?.http?.dispatcher?.executorService?.shutdown()
                other?.http?.connectionPool?.evictAll()
            }
        }
    }
}
