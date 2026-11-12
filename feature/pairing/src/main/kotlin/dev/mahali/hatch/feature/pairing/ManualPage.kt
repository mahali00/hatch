package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.design.HatchFieldLabel
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchField
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.HostKindPicker
import dev.mahali.hatch.design.TailscaleNotice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostKind
import dev.mahali.hatch.core.HostProbe
import dev.mahali.hatch.design.chrome
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun ManualPage(
    probe: HostProbe,
    existing: List<Host>,
    initialAddress: String,
    initialProblem: Attempt.Problem?,
    onBack: () -> Unit,
    onAdded: (Host) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(initialAddress) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(HostKind.MacBook) }
    var problem by remember { mutableStateOf(initialProblem) }
    var busy by remember { mutableStateOf(false) }
    val canConnect = address.isNotBlank() && !busy
    val connect = {
        busy = true
        scope.launch {
            when (val attempt = attemptConnect(probe, existing, address)) {
                is Attempt.Reached -> {
                    val chosen = name.trim().ifEmpty { attempt.suggestedName ?: defaultHostName(attempt.url) }
                    onAdded(Host(HostId(UUID.randomUUID().toString()), chosen, attempt.url, kind))
                }
                is Attempt.Problem -> problem = attempt
            }
            busy = false
        }
        Unit
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitle("Add Host", onBack)
        SheetBody("Enter the address and details of your Rex server to connect from this device.", Modifier.padding(bottom = 6.dp))
        HatchFieldLabel("Server address")
        HatchField(address, { address = it }, "studio.tailnet.ts.net", keyboardType = KeyboardType.Uri)
        HatchFieldLabel("Host details")
        HatchField(
            name, { name = it }, "Name (optional)", imeAction = ImeAction.Done,
            onDone = { if (canConnect) connect() },
            leading = { HostKindPicker(kind) { kind = it } },
        )
        val shown = problem
        if (shown != null) {
            MessageCard(shown.title, chrome.accent, detail = shown.detail)
        } else {
            if (address.trim().startsWith("http://", ignoreCase = true)) Note("Plain HTTP is not encrypted. Use it only on a network you trust, such as Tailscale.", warning = true)
            Note("Rex does not provide transport authentication. Use a trusted tunnel such as Tailscale.")
        }
        TailscaleNotice(address)
        val label = when {
            busy -> "Connecting…"
            shown?.title == COULD_NOT_ADD -> "Try Again"
            else -> "Connect"
        }
        SheetButton(label, onClick = connect, enabled = canConnect)
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(text, Modifier.padding(horizontal = Dimens.CardPadding), style = HatchType.Footnote, color = if (warning) chrome.warning else chrome.secondary)
}
