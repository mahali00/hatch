package dev.mahali.hatch.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction

/** Unfolded, it docks as the key bar's pill, so the two line up on the keyboard's edge. */
@Composable
public fun HatchSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    description: String = placeholder,
    autoFocus: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(focus) { focus.requestFocus() }
    val unfolded = Hatch.scale.unfolded
    val height = if (unfolded) Hatch.size.imeBar else Hatch.size.floatingButton
    Row(
        modifier.fillMaxWidth().height(height).clip(HatchShapes.Capsule)
            .background(if (unfolded) chrome.card else floatingBarFill())
            .padding(start = if (unfolded) Hatch.space.m else Hatch.space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Hatch.layout.iconGap),
    ) {
        Icon(HatchIcons.Search, contentDescription = null, Modifier.size(Hatch.size.icon), tint = chrome.secondary)
        BasicTextField(
            query, onQueryChange,
            Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = description },
            singleLine = true,
            textStyle = Hatch.type.body.merge(color = chrome.text),
            cursorBrush = SolidColor(chrome.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, style = Hatch.type.body, color = chrome.secondary)
                    field()
                }
            },
        )
        when {
            onClose != null -> HeaderButton(HatchIcons.Close, "Close search", onClose, tint = chrome.secondary, size = ButtonSize.Bar)
            query.isNotEmpty() -> HeaderButton(HatchIcons.Close, "Clear search", { onQueryChange(""); keyboard?.hide() }, tint = chrome.secondary, size = ButtonSize.Bar)
        }
    }
}
