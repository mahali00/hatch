package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchField
import dev.mahali.hatch.design.HatchSheetHeader
import dev.mahali.hatch.design.HostKindPicker
import dev.mahali.hatch.design.chrome
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostKind
import java.util.UUID

@Composable
internal fun ConnectedPage(url: String, suggestedName: String, onDone: (Host) -> Unit) {
    var name by remember { mutableStateOf(suggestedName) }
    var kind by remember { mutableStateOf(HostKind.MacBook) }
    val done = { onDone(Host(HostId(UUID.randomUUID().toString()), name.trim().ifEmpty { suggestedName }, url, kind)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenInset), horizontalAlignment = Alignment.CenterHorizontally) {
        HatchSheetHeader("Connected to Host")
        SheetBody("You are adding this server. The name below is only a label.", Modifier.padding(top = Hatch.space.s, bottom = Hatch.space.m))
        Text(
            url,
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
            style = Hatch.type.mono,
            color = chrome.text,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        HatchField(
            name, { name = it }, "Name", imeAction = ImeAction.Done,
            onDone = done,
            leading = { HostKindPicker(kind) { kind = it } },
        )
        Spacer(Modifier.height(22.dp))
        SheetButton("Done", onClick = done)
    }
}
