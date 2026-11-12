package dev.mahali.hatch.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

// Sizes come from the current scale, so a page never sets its own height, font or gap.

@Composable
public fun HatchSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    titleWeight: FontWeight? = null,
) {
    HatchListRow(
        title, subtitle = subtitle, titleWeight = titleWeight, onClick = { onChange(!checked) },
        trailing = { HatchSwitch(checked, onChange, description = title) },
    )
}

@Composable
public fun <T> HatchChoiceRow(
    title: String,
    value: T,
    options: List<T>,
    label: (T) -> String,
    onChoose: (T) -> Unit,
    icon: (@Composable (option: T, selected: Boolean) -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val trailing = LocalListMetrics.current.choiceTrailing
    Box {
        if (trailing) HatchListRow(
            onClick = { open = true }, leading = leading,
            trailing = {
                Text(label(value), style = Hatch.type.body, color = chrome.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(HatchIcons.ChevronUpDown, null, Modifier.size(Hatch.size.iconSmall), tint = chrome.secondary)
            },
        ) { Text(title, style = Hatch.type.body, color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        else HatchListRow(title, subtitle = label(value), subtitleColor = chrome.accent, onClick = { open = true }, leading = leading)
        HatchMenu(open, { open = false }, Modifier.padding(start = Hatch.layout.screenInset), alignEnd = trailing, below = Hatch.space.xxs) {
            for (option in options) {
                val selected = option == value
                HatchMenuItem(
                    label(option), { open = false; onChoose(option) },
                    color = if (selected) chrome.accent else chrome.text,
                    leading = if (icon != null) ({ icon(option, selected) }) else null,
                    trailing = if (selected) ({ SelectedCheck() }) else null,
                )
            }
        }
    }
}

@Composable
public fun HatchValueRow(title: String, value: String, mono: Boolean = false) {
    HatchListRow(
        trailing = {
            Text(
                value, Modifier.weight(1f), textAlign = TextAlign.End, color = chrome.secondary,
                style = if (mono) Hatch.type.mono else Hatch.type.callout,
            )
        },
    ) { Text(title, style = Hatch.type.body, color = chrome.text, maxLines = 1) }
}

@Composable
public fun HatchStepperRow(
    title: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    decrease: () -> Unit,
    increase: () -> Unit,
) {
    HatchListRow(title, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.s)) {
            Text(value, style = Hatch.type.callout, color = chrome.secondary)
            Row(Modifier.clip(HatchShapes.Capsule).background(chrome.control)) {
                StepButton("Decrease $title", HatchIcons.Minus, canDecrease, decrease)
                StepButton("Increase $title", HatchIcons.Plus, canIncrease, increase)
            }
        }
    })
}

@Composable
private fun StepButton(description: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(Hatch.size.control).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(Hatch.size.icon), tint = if (enabled) chrome.text else chrome.secondary.copy(alpha = 0.4f)) }
}

@Composable
public fun Revealed(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val motion = Hatch.motion
    AnimatedVisibility(
        visible,
        enter = expandVertically(motion.spec()) + fadeIn(motion.spec()),
        exit = shrinkVertically(motion.spec()) + fadeOut(motion.spec()),
    ) { Column(content = content) }
}

@Composable
public fun CardSpacer(): Unit = Spacer(Modifier.height(Hatch.layout.sectionGap))
