package dev.mahali.hatch.core

import java.net.URI

public fun normalizeAddress(input: String): Result<String> {
    val text = input.trim()
    // Add the scheme first: without it, "studio:7000" parses with scheme "studio".
    val uri = runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val host = uri?.host?.lowercase()
    val defaultPort = when (scheme) { "https" -> 443; "http" -> 80; else -> null }
    if (uri == null || host.isNullOrEmpty() || defaultPort == null || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || (uri.port != -1 && uri.port !in 1..65535)
    ) return Result.failure(IllegalArgumentException(INVALID_SERVER_ADDRESS))
    // One server, one spelling: default port and trailing slash dropped for the duplicate check.
    val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
    return Result.success("$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}")
}

public const val INVALID_SERVER_ADDRESS: String = "Enter an HTTP or HTTPS URL, or a bare hostname and port."
