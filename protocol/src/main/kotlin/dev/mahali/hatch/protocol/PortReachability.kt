package dev.mahali.hatch.protocol

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns

/** A TCP check at a recorded time, independent of the Rex application handshake. */
public data class PortReachability(
    val port: Int,
    val reachable: Boolean,
    val millis: Long?,
    val checkedAtMillis: Long,
    val failure: String? = null,
    val address: String? = null,
    val resolveMillis: Long? = null,
)

internal suspend fun probePort(host: String, port: Int, dns: Dns, timeoutMillis: Int = 2_000): PortReachability = withContext(Dispatchers.IO) {
    val start = System.nanoTime()
    var failure = "No resolved addresses"
    var resolved: Long? = null
    try {
        val addresses = dns.lookup(host)
        resolved = (System.nanoTime() - start) / 1_000_000
        for (address in addresses) {
            val remaining = timeoutMillis - (System.nanoTime() - start) / 1_000_000
            if (remaining <= 0) { failure = "Timed out"; break }
            try {
                Socket().use { it.connect(InetSocketAddress(address, port), remaining.toInt()) }
                return@withContext PortReachability(port, true, (System.nanoTime() - start) / 1_000_000, System.currentTimeMillis(),
                    address = "${if (address is java.net.Inet6Address) "[${address.hostAddress}]" else address.hostAddress}:$port", resolveMillis = resolved)
            } catch (e: IOException) { failure = e.message ?: e.javaClass.simpleName }
        }
    } catch (e: IOException) { failure = e.message ?: e.javaClass.simpleName }
    PortReachability(port, false, null, System.currentTimeMillis(), failure, resolveMillis = resolved)
}
