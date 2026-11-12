package dev.mahali.hatch.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt

@Composable
public fun <T> HatchSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
) {
    val motion = Hatch.motion
    Row(
        modifier.height(Hatch.size.controlCompact).clip(HatchShapes.Capsule).background(chrome.control)
            .padding(Hatch.space.xxs).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            val chosen = option == selected
            val fill by animateColorAsState(if (chosen) chrome.card else Color.Transparent, motion.spec(motion.quick), label = "segment")
            Row(
                Modifier.fillMaxHeight().widthIn(min = Hatch.size.controlCompact).clip(HatchShapes.Capsule).background(fill)
                    .selectable(chosen, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = Hatch.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Hatch.space.xs, Alignment.CenterHorizontally),
            ) {
                icon?.invoke(option)?.let { Icon(it, null, Modifier.size(Hatch.size.iconSmall), tint = if (chosen) chrome.text else chrome.secondary) }
                Text(label(option), style = Hatch.type.callout, color = if (chosen) chrome.text else chrome.secondary, maxLines = 1)
            }
        }
    }
}

@Composable
public fun <T> HatchSegmentedRow(
    title: String,
    selected: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    icon: ((T) -> ImageVector?)? = null,
) {
    HatchListRow(trailing = { HatchSegmented(options, selected, label, onSelect, icon = icon) }) {
        Text(title, style = Hatch.type.body, color = chrome.text, maxLines = 1)
    }
}

@Composable
public fun HatchSliderRow(title: String, value: Int, steps: IntRange, valueLabel: String, onChange: (Int) -> Unit) {
    HatchListRow(trailing = {
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            valueRange = steps.first.toFloat()..steps.last.toFloat(), steps = (steps.last - steps.first - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = chrome.accent, activeTrackColor = chrome.accent, inactiveTrackColor = chrome.control),
        )
        Box(Modifier.widthIn(min = Hatch.size.control), contentAlignment = Alignment.CenterEnd) {
            Text(valueLabel, style = Hatch.type.callout, color = chrome.secondary, maxLines = 1)
        }
    }) { Text(title, style = Hatch.type.body, color = chrome.text, maxLines = 1) }
}

@Composable
public fun HatchStepperField(
    label: String,
    value: String,
    icon: ImageVector,
    unit: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    decrease: () -> Unit,
    increase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Hatch.space.xs)) {
        Text(label, style = Hatch.type.footnote, color = chrome.secondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.s)) {
            Row(
                Modifier.height(Hatch.size.touchTarget).clip(HatchShapes.Small).background(chrome.control).padding(start = Hatch.space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(Hatch.size.iconSmall), tint = chrome.secondary)
                Text(value, Modifier.padding(horizontal = Hatch.space.s).widthIn(min = Hatch.space.xxl), style = Hatch.type.body, color = chrome.text)
                Column(Modifier.fillMaxHeight()) {
                    StepHalf("Increase $label", HatchIcons.ChevronUp, canIncrease, increase)
                    StepHalf("Decrease $label", HatchIcons.ChevronDown, canDecrease, decrease)
                }
            }
            Text(unit, style = Hatch.type.body, color = chrome.secondary)
        }
    }
}

@Composable
private fun ColumnScope.StepHalf(description: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).width(Hatch.size.controlCompact).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(Hatch.size.smallChevron), tint = if (enabled) chrome.text else chrome.secondary.copy(alpha = 0.4f)) }
}
