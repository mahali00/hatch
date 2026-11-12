package dev.mahali.hatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet6Address

@Suppress("DEPRECATION")
internal fun networkPath(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "No network"
    val active = cm.activeNetwork ?: return "No network"
    val caps = cm.getNetworkCapabilities(active) ?: return "No network"
    val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    fun transport(c: NetworkCapabilities): String = when {
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Network"
    }
    val via = transport(caps)
    val links = cm.getLinkProperties(active)
    val interfaces = mutableListOf<String>()
    if (vpn) {
        cm.allNetworks.filter { it != active }.forEach { network ->
            val c = cm.getNetworkCapabilities(network) ?: return@forEach
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@forEach
            if (via != "Network" && transport(c) != via) return@forEach
            cm.getLinkProperties(network)?.interfaceName?.let { interfaces += "$it ${transport(c)} (available)" }
        }
    }
    links?.interfaceName?.let { interfaces += "$it ${if (vpn) "tunnel" else via}" }
    val families = links?.linkAddresses.orEmpty().map { if (it.address is Inet6Address) "IPv6" else "IPv4" }.distinct()
    val details = (interfaces.distinct() + families + if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) emptyList() else listOf("not validated")).joinToString(" · ")
    return "$via${if (vpn) " + VPN" else ""}" + if (details.isNotEmpty()) " · $details" else ""
}
