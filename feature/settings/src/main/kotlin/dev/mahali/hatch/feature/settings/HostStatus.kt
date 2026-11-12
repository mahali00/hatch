package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.Link

internal fun statusWord(link: Link?): String = when (link) {
    Link.Online -> "Connected"
    is Link.Offline -> "Offline"
    is Link.Connecting -> if (link.retryAtMillis != null || link.lastError != null) "Reconnecting" else "Connecting…"
    null -> "Connecting…"
}

internal fun statusWord(host: Host, link: Link?): String = if (host.enabled) statusWord(link) else "Paused"

internal fun lastError(link: Link?): String? = when (link) {
    is Link.Offline -> link.reason.ifBlank { null }
    is Link.Connecting -> link.lastError?.ifBlank { null }
    Link.Online, null -> null
}

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
