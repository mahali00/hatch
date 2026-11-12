package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

class ConnectionErrorTest {
    @Test fun `an unresolved tailnet name points at MagicDNS`() {
        assertEquals(
            "Couldn't find studio.tailnet.ts.net. Turn on MagicDNS in Tailscale, or use the server's 100.x address.",
            failureReason(UnknownHostException("Unable to resolve host"), "studio.tailnet.ts.net"),
        )
        assertEquals("Unable to resolve host", failureReason(UnknownHostException("Unable to resolve host"), "studio.lan"))
        assertEquals("Connection refused", failureReason(IOException("Connection refused"), "studio.tailnet.ts.net"))
    }

    @Test fun `a retry stays offline with the last error until a connection succeeds`() = runBlocking {
        val firstFails = CountDownLatch(1)
        val secondFails = CountDownLatch(1)
        val gate = AtomicReference(firstFails)
        val http = OkHttpClient.Builder().dns { name ->
            gate.get().await()
            throw UnknownHostException(name)
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val control = RexControl(http, "https://studio.lan", { ClientInfo(version = "test", label = "test") }, scope)
        val sync = HostSync(Host(HostId("h"), "Studio", "https://studio.lan"), control, scope)
        try {
            sync.start()
            // A first connect with no failure yet is plain "Connecting".
            assertEquals(ControlStatus.Connecting(0), control.status.value)
            firstFails.countDown()
            withTimeout(5000) { sync.state.first { (it.link as? Link.Offline)?.retryAtMillis != null } }
            gate.set(secondFails) // the retry hangs in DNS, so only the carried error can be showing
            sync.reconnect()
            withTimeout(5000) { sync.state.first { it.link == Link.Offline("studio.lan") } }
            assertEquals(ControlStatus.Connecting(0, lastError = "studio.lan"), control.status.value)
        } finally {
            secondFails.countDown()
            control.stop()
            scope.cancel()
            http.dispatcher.executorService.shutdown()
        }
    }
}
