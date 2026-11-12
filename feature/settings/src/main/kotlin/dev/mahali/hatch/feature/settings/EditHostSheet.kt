package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchNotice
import dev.mahali.hatch.design.HatchFieldLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.normalizeAddress
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HostKindPicker
import dev.mahali.hatch.design.HatchButton
import dev.mahali.hatch.design.HatchField
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchSheet
import dev.mahali.hatch.design.HatchSheetHeader
import dev.mahali.hatch.design.HatchSwitchRow
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.TailscaleNotice
import dev.mahali.hatch.design.chrome

@Composable
public fun EditHostSheet(
    host: Host,
    hostState: HostState?,
    onSave: (Host) -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable(host.id.value) { mutableStateOf(host.url) }
    var name by rememberSaveable(host.id.value) { mutableStateOf(host.name) }
    var kind by rememberSaveable(host.id.value) { mutableStateOf(host.kind) }
    var command by rememberSaveable(host.id.value) { mutableStateOf(host.startupCommand) }
    var enabled by rememberSaveable(host.id.value) { mutableStateOf(host.enabled) }
    var addressError by remember { mutableStateOf<String?>(null) }
    // A cleared field keeps the saved value: a host always has an address and a name.
    val save = {
        normalizeAddress(url.trim().ifEmpty { host.url }).fold(
            onSuccess = { address -> onSave(host.copy(url = address, name = name.trim().ifEmpty { host.name }, kind = kind, startupCommand = command.trim(), enabled = enabled)) },
            onFailure = { addressError = it.message },
        )
    }

    HatchSheet(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(start = Dimens.ScreenInset, end = Dimens.ScreenInset, bottom = Hatch.space.xl),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HatchSheetHeader(
                "Edit Host",
                leading = { HeaderButton(HatchIcons.Close, "Close", onDismiss) },
                trailing = { HeaderButton(HatchIcons.Check, "Save", save, tint = chrome.accent) },
            )
            Text(
                "Update the details for this remote host.", Modifier.fillMaxWidth().padding(bottom = Hatch.space.xs),
                style = HatchType.Callout, color = chrome.secondary, textAlign = TextAlign.Center,
            )
            HatchFieldLabel("Server address")
            HatchField(url, { url = it; addressError = null }, "Server address", keyboardType = KeyboardType.Uri)
            addressError?.let { Text(it, style = HatchType.Footnote, color = chrome.danger) }
            if (host.enabled && hostState?.link != Link.Online) {
                val link = hostState?.link
                HatchNotice(
                    lastError(link), title = statusWord(link), background = chrome.background,
                    leading = { Box(Modifier.size(Hatch.space.s).clip(CircleShape).background(chrome.statusColor(link))) },
                    action = if (link is Link.Offline) "Retry" else null, onAction = onRetry,
                )
            }
            TailscaleNotice(url)
            HatchFieldLabel("Host details")
            HatchField(name, { name = it }, "Name", autoCorrect = true, leading = { HostKindPicker(kind) { kind = it } })
            HatchFieldLabel("Startup command")
            HatchField(command, { command = it }, "Default login shell", textStyle = HatchType.Body.copy(fontFamily = Settings.FontFamily.JetBrainsMono.compose()))
            Text(
                "Executable and arguments for new terminals on this host. Use quotes to group arguments. " +
                    "Leave empty to use the host's default login shell.",
                Modifier.padding(horizontal = Dimens.FieldPadding), style = HatchType.Footnote, color = chrome.secondary,
            )
            HatchSwitchRow("Connect to This Host", enabled, { enabled = it }, subtitle = "Turn off to pause this host without removing it.")
            HatchButton(
                "Remove Host", onRemove, Modifier.fillMaxWidth().padding(top = Hatch.space.s),
                container = Color.Transparent, content = chrome.danger,
            )
        }
    }
}

