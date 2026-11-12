package dev.mahali.hatch.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.HostKind

@Composable
public fun HostKindPicker(kind: HostKind, onPick: (HostKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.size(Dimens.TouchTarget).clip(CircleShape).clickable(role = Role.DropdownList) { open = true }
                .semantics { contentDescription = "Device type, ${kind.label}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.Start),
        ) {
            Icon(kind.icon, null, Modifier.size(Dimens.Icon), tint = chrome.text)
            Icon(HatchIcons.ChevronUpDown, null, Modifier.size(Dimens.SmallChevron), tint = chrome.secondary)
        }
        HatchMenu(open, { open = false }, alignEnd = false) {
            HostKind.entries.forEach { option ->
                HatchMenuItem(option.label, { open = false; onPick(option) }, option.icon,
                    color = if (option == kind) chrome.accent else chrome.text,
                    trailing = { if (option == kind) Icon(HatchIcons.Check, "Selected", Modifier.size(18.dp), tint = chrome.accent) })
            }
        }
    }
}
