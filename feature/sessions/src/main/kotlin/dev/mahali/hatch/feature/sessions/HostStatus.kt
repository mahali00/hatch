package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal enum class HostNotice { Loading, Offline, Replaced, Empty }

internal fun HostState.notice(): HostNotice? = when {
    link is Link.Offline -> HostNotice.Offline
    replacedServer -> HostNotice.Replaced
    sessions.isNotEmpty() -> null
    link is Link.Connecting -> HostNotice.Loading
    else -> HostNotice.Empty
}

internal fun linkStatus(link: Link, nowMillis: Long): String? = when (link) {
    Link.Online -> null
    is Link.Connecting -> "Connecting"
    is Link.Offline -> {
        val seconds = link.retryAtMillis?.let { (it - nowMillis + 999) / 1000 } ?: 0
        if (seconds > 0) "Offline · retrying in ${seconds}s" else "Offline · retrying"
    }
}

internal fun formatAge(sinceMillis: Long, nowMillis: Long): String {
    val minutes = (nowMillis - sinceMillis).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

/** Parses the server's RFC 3339 timestamps; anything else is null. */
internal fun parseInstantMillis(rfc3339: String): Long? = try {
    OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}
