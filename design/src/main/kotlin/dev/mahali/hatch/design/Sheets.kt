package dev.mahali.hatch.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun HatchSheet(
    onDismiss: () -> Unit,
    container: Color = chrome.card,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = HatchShapes.Sheet,
        containerColor = container,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(HatchShapes.Capsule).background(chrome.stroke))
        },
        content = content,
    )
}

@Composable
public fun HatchSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Dimens.TouchTarget)) { leading?.invoke() }
        Text(title, Modifier.weight(1f), style = HatchType.Title, color = chrome.text, textAlign = TextAlign.Center)
        Box(Modifier.size(Dimens.TouchTarget)) { trailing?.invoke() }
    }
}

@Composable
public fun HatchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    autoCorrect: Boolean = false,
    textStyle: TextStyle = HatchType.Body,
    onDone: () -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(Dimens.ControlHeight).clip(HatchShapes.Capsule).background(chrome.control)
            .border(2.dp, if (focused) chrome.accent else Color.Transparent, HatchShapes.Capsule)
            .padding(horizontal = Dimens.FieldPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = textStyle, color = chrome.secondary, maxLines = 1)
            BasicTextField(
                value, onValueChange,
                Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder },
                singleLine = true,
                textStyle = textStyle.copy(color = chrome.text),
                cursorBrush = SolidColor(chrome.accent),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = autoCorrect, keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
            )
        }
    }
}

@Composable
public fun HatchRenameDialog(title: String, current: String, onSave: (String) -> Unit, onDismiss: () -> Unit, fieldDescription: String = title) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    val save = { onSave(value.text.trim()) }
    val accent = chrome.accent
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = Dimens.ScreenInset).fillMaxWidth().clip(HatchShapes.Card).background(chrome.card)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = HatchType.Title, color = chrome.text)
            BasicTextField(
                value, { value = it },
                Modifier.fillMaxWidth().height(44.dp).focusRequester(focus).semantics { contentDescription = fieldDescription }
                    .drawBehind {
                        val y = size.height - 1.dp.toPx()
                        drawLine(accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                    },
                singleLine = true,
                textStyle = HatchType.Body.copy(color = chrome.text),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                decorationBox = { field -> Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { field() } },
            )
            Row(Modifier.fillMaxWidth().height(Dimens.TouchTarget), verticalAlignment = Alignment.CenterVertically) {
                DialogButton("Cancel", chrome.text, onDismiss, Modifier.weight(1f))
                Spacer(Modifier.size(1.dp, 20.dp).background(chrome.divider))
                DialogButton("Save", accent, save, Modifier.weight(1f))
            }
        }
    }
}

/** [onConfirm] runs after the dialog closes. */
@Composable
public fun HatchConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = Dimens.ScreenInset).fillMaxWidth().clip(HatchShapes.Card).background(chrome.card)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = HatchType.Title, color = chrome.text)
            Text(message, style = HatchType.Body, color = chrome.secondary)
            Row(Modifier.fillMaxWidth().height(Dimens.TouchTarget), verticalAlignment = Alignment.CenterVertically) {
                DialogButton("Cancel", chrome.text, onDismiss, Modifier.weight(1f))
                DialogButton(confirm, chrome.danger, { onDismiss(); onConfirm() }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxHeight().clip(HatchShapes.Capsule).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = HatchType.Body, color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
