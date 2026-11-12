package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.design.Hatch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostProbe
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchSheet
import dev.mahali.hatch.design.HatchSheetHeader

private sealed interface Page {
    data object Choose : Page
    data object Scan : Page
    data class Instructions(val os: Os) : Page
    data class Manual(val address: String = "", val problem: Attempt.Problem? = null) : Page
    data class Connected(val url: String, val name: String) : Page
}

@Composable
public fun AddHostSheet(probe: HostProbe, existing: List<Host>, onAdded: (Host) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<Page>(Page.Choose) }
    var added by remember { mutableStateOf(false) }
    val add = { host: Host -> if (!added) { added = true; onAdded(host) } }
    HatchSheet(onDismiss) {
        val back = {
            page = when (page) {
                is Page.Instructions -> Page.Scan
                else -> Page.Choose
            }
        }
        BackHandler(enabled = page != Page.Choose, onBack = back)
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = Hatch.space.l)) {
            when (val current = page) {
                Page.Choose -> ChoosePage(onManual = { page = Page.Manual() }, onScan = { page = Page.Scan })
                Page.Scan -> ScanPage(
                    probe, existing,
                    onBack = back,
                    onInstructions = { page = Page.Instructions(it) },
                    onManual = { page = Page.Manual() },
                    onReached = { url, name -> page = Page.Connected(url, name) },
                    onProblem = { address, problem -> page = Page.Manual(address, problem) },
                )
                is Page.Instructions -> InstructionsPage(current.os, onBack = back, onPair = { page = Page.Scan })
                is Page.Manual -> ManualPage(probe, existing, current.address, current.problem, onBack = back, onAdded = add)
                is Page.Connected -> ConnectedPage(current.url, current.name, onDone = add)
            }
        }
    }
}

@Composable
private fun ChoosePage(onManual: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenInset), horizontalAlignment = Alignment.CenterHorizontally) {
        HatchSheetHeader("Connect to Remote Host")
        SheetBody(ADD_HOST_BODY, Modifier.padding(top = Hatch.space.s))
        Column(Modifier.fillMaxWidth().padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(Dimens.IconGap)) {
            SheetButton("Set Up Manually", onClick = onManual, filled = false)
            SheetButton("Pair via QR Code", onClick = onScan, qrIcon = true)
        }
    }
}

internal const val ADD_HOST_BODY = "Pair automatically via Tailscale, or enter your Rex server details manually."
