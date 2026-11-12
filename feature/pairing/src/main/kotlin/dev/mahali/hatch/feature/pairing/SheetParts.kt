package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchNotice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchSheetHeader
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome

// What the Add Host sheet's pages add to :design's pieces.

@Composable
internal fun SheetButton(label: String, onClick: () -> Unit, filled: Boolean = true, qrIcon: Boolean = false, enabled: Boolean = true) {
    HatchButton(
        label, onClick, Modifier.fillMaxWidth(),
        container = if (filled) chrome.accent else chrome.control,
        content = if (filled) chrome.onAccent else chrome.text,
        icon = if (qrIcon) HatchIcons.Qr else null,
        enabled = enabled,
    )
}

@Composable
internal fun SheetTitle(title: String, onBack: () -> Unit) {
    HatchSheetHeader(title, leading = { HeaderButton(HatchIcons.ChevronLeft, "Back", onBack) })
}

@Composable
internal fun SheetBody(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = Hatch.space.m), style = HatchType.Callout, color = chrome.secondary, textAlign = TextAlign.Center)
}

@Composable
internal fun MessageCard(
    title: String,
    iconTint: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    background: Color = chrome.background,
    textColor: Color = chrome.text,
) {
    HatchNotice(
        detail ?: title, modifier, title = if (detail != null) title else null, background = background, textColor = textColor,
        leading = { Icon(HatchIcons.Alert, null, Modifier.size(Hatch.size.icon), tint = iconTint) },
    )
}
