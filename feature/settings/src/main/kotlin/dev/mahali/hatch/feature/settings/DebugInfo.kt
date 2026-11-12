package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.Settings
import java.net.URI

internal fun versionInfo(about: AboutInfo): String =
    "Hatch ${about.version}\n${about.device}\nTerminal engine: ${about.engine}"

internal fun debugInfo(about: AboutInfo, settings: Settings, hosts: List<Host>, state: AppState): String = buildString {
    appendLine(versionInfo(about))
    appendLine()
    appendLine("Appearance: ${settings.appearance}, light ${settings.lightTheme}, dark ${settings.darkTheme}, tint ${settings.tint}")
    appendLine("Font: ${settings.fontFamily.label} ${settings.fontSize} pt, cursor ${settings.cursorStyle}, blink ${settings.blink}")
    appendLine("Notifications: ${settings.notifications}, stay connected ${settings.stayConnected}")
    appendLine("Hosts: ${hosts.size}")
    for (host in hosts) {
        val link = state.byHost[host.id]?.link
        append("  ${host.name}: ${statusWord(link)}")
        lastError(link)?.let { append(" (${redactAddresses(it, hosts)})") }
        appendLine()
    }
}.trimEnd()

internal fun redactAddresses(text: String, hosts: List<Host>): String = hosts.fold(text) { acc, host ->
    // The full address first, so its scheme and port go with it; then the bare machine name.
    listOfNotNull(host.url.takeIf { it.isNotBlank() }, machineName(host.url))
        .fold(acc) { t, address -> t.replace(address, host.name, ignoreCase = true) }
}

private fun machineName(url: String): String? =
    runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
