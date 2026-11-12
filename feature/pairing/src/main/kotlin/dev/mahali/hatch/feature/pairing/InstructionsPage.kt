package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.chrome

internal enum class Os(val label: String) { MacOS("macOS"), Linux("Linux") }

@Composable
internal fun InstructionsPage(os: Os, onBack: () -> Unit, onPair: () -> Unit) {
    // No iOS capture of these pages: the design's sizes, scaled as the measured sheets scale (body 16 → 15 sp, gaps 16 → 14 dp).
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenInset), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SheetTitle("Connect to ${os.label}", onBack)
        Column(Modifier.padding(horizontal = Hatch.space.s), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (os) {
                Os.MacOS -> MacOsSteps()
                Os.Linux -> LinuxSteps()
            }
        }
        SheetButton("Pair via QR Code", onClick = onPair, qrIcon = true)
    }
}

@Composable
private fun MacOsSteps() {
    Step(1, rich("Click the ⌘ button in the toolbar"))
    Step(2, rich("Choose the **Pair Device with QR Code** command from the menu"))
    Step(3, rich("Scan the QR code"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.m)) {
        HorizontalDivider(Modifier.weight(1f), 1.dp, chrome.divider)
        Text("or", style = HatchType.Footnote, color = chrome.secondary)
        HorizontalDivider(Modifier.weight(1f), 1.dp, chrome.divider)
    }
    Step(1, rich("Open the Rex **Settings** window"))
    Step(2, rich("From the **Server** section, click the **Pair** button to open the pairing sheet"))
    Step(3, rich("Scan the QR code"))
}

@Composable
private fun LinuxSteps() {
    Step(1, rich("Open the terminal application on your Linux server"))
    Step(2, rich("Run the following command")) {
        Text(
            "rex server tailscale pair", style = Hatch.type.mono, color = chrome.text,
            modifier = Modifier.clip(HatchShapes.Small).background(chrome.control).padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
    Step(3, rich("Scan the QR code"))
}

@Composable
private fun Step(number: Int, text: AnnotatedString, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.IconGap)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(chrome.control), contentAlignment = Alignment.Center) {
            Text("$number", style = HatchType.Footnote, color = chrome.secondary)
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(Hatch.space.s)) {
            Text(text, style = HatchType.Callout, color = chrome.text)
            extra?.invoke()
        }
    }
}

private fun rich(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}
