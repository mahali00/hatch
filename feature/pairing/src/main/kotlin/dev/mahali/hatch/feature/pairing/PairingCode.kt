package dev.mahali.hatch.feature.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.mahali.hatch.core.normalizeAddress
import dev.mahali.hatch.core.INVALID_SERVER_ADDRESS
import java.net.URI

public data class PairingCode(val url: String, val name: String?)

/** The server address in a pairing QR code (an http(s) URL, host:port, or JSON with url and optional name), or null. */
public fun parsePairingCode(text: String): PairingCode? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return codeAddress(trimmed)?.let { PairingCode(it, null) }
    val json = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
    val url = json.string("url") ?: return null
    return codeAddress(url)?.let { PairingCode(it, json.string("name")?.trim()?.ifEmpty { null }) }
}

internal fun defaultHostName(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull().orEmpty()
    val ip = host.startsWith("[") || host.none { it.isLetter() }
    return if (ip) host else host.substringBefore('.')
}

internal const val INVALID_ADDRESS = INVALID_SERVER_ADDRESS

private fun codeAddress(text: String): String? =
    if ("://" in text || HostAndPort.matches(text)) normalizeAddress(text).getOrNull() else null

private val HostAndPort = Regex("""([A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*|\[[0-9A-Fa-f:.]+]):\d{1,5}""")

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
