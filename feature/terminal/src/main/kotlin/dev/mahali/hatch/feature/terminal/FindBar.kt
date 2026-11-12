package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.ButtonSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.floatingEdge
import dev.mahali.hatch.design.floatingFill
import dev.mahali.hatch.ghostty.SearchInfo

// A floating capsule: opening it must not resize the panes, which re-wraps every line on the host.
@Composable
internal fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    info: SearchInfo?,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Row(
        modifier.widthIn(max = 420.dp).height(44.dp).clip(HatchShapes.Capsule).background(floatingFill()).floatingEdge(HatchShapes.Capsule)
            .padding(start = 14.dp, end = Hatch.space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(HatchIcons.Search, null, Modifier.size(18.dp), tint = chrome.secondary)
        BasicTextField(
            query, onQuery,
            Modifier.weight(1f).focusRequester(focus).onFocusChanged { onFocus(it.isFocused) }
                .semantics { contentDescription = "Find in pane" },
            singleLine = true,
            textStyle = HatchType.Callout.copy(color = chrome.text),
            cursorBrush = SolidColor(chrome.accent),
            // Literal text: no autocorrect or capitals (Gboard turned "needle" into "Ineedl").
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onOlder() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Find", style = HatchType.Callout, color = chrome.secondary)
                    field()
                }
            },
        )
        if (query.isNotEmpty()) Text(findCount(info), style = HatchType.Footnote, color = chrome.secondary, maxLines = 1)
        HeaderButton(HatchIcons.ChevronUp, "Older match", onOlder, size = ButtonSize.Toolbar)
        HeaderButton(HatchIcons.ChevronDown, "Newer match", onNewer, size = ButtonSize.Toolbar)
        HeaderButton(HatchIcons.Close, "Close find", onClose, size = ButtonSize.Toolbar)
    }
}

internal fun findCount(info: SearchInfo?): String {
    if (info == null) return ""
    val more = if (info.complete) "" else "…"
    val selected = info.selected
    return when {
        info.total == 0 -> if (info.complete) "None" else "…"
        selected != null -> "${selected + 1} of ${info.total}$more"
        else -> "${info.total}$more"
    }
}
