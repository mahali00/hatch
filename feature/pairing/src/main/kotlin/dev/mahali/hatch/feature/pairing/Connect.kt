package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.core.normalizeAddress
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostProbe
import dev.mahali.hatch.core.ProbeResult

internal sealed interface Attempt {
    data class Reached(val url: String, val suggestedName: String?) : Attempt
    data class Problem(val title: String, val detail: String? = null) : Attempt
}

internal suspend fun attemptConnect(probe: HostProbe, existing: List<Host>, address: String): Attempt {
    val url = normalizeAddress(address).getOrElse { return Attempt.Problem(INVALID_ADDRESS) }
    if (existing.any { it.url == url }) return Attempt.Problem(ALREADY_SAVED)
    return when (val result = probe.probe(url)) {
        is ProbeResult.Reached -> Attempt.Reached(result.url, result.suggestedName)
        is ProbeResult.Failed -> Attempt.Problem(COULD_NOT_ADD, result.message)
    }
}

internal const val ALREADY_SAVED = "That remote host is already saved."
internal const val COULD_NOT_ADD = "The remote host couldn't be added."
