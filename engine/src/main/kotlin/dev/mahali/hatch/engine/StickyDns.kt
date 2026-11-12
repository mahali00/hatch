package dev.mahali.hatch.engine

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Tailscale MagicDNS is briefly down after the phone wakes or changes network, holding reconnects
 * for tens of seconds. TLS still checks the host name, so a stale address can only fail to connect.
 */
internal class StickyDns(private val system: Dns = Dns.SYSTEM) : Dns {
    private val known = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> = try {
        system.lookup(hostname).also { if (it.isNotEmpty()) known[hostname] = it }
    } catch (e: UnknownHostException) {
        known[hostname] ?: throw e
    }
}
