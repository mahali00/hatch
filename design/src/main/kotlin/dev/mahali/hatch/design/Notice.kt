package dev.mahali.hatch.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

/** Host problems, pairing errors and network hints all use this, so they read alike. */
@Composable
public fun HatchNotice(
    message: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    leading: (@Composable () -> Unit)? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    background: Color = chrome.card,
    textColor: Color = chrome.text,
) {
    Row(
        modifier.fillMaxWidth().clip(HatchShapes.Card).background(background)
            .padding(horizontal = Hatch.layout.cardPadding, vertical = Hatch.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Hatch.layout.iconGap),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Hatch.space.xxs)) {
            if (title != null) Text(title, style = Hatch.type.label, color = textColor)
            if (!message.isNullOrEmpty()) Text(message, style = Hatch.type.callout, color = if (title == null) textColor else chrome.secondary)
        }
        if (action != null) HatchPillButton(action, onAction, color = textColor)
    }
}
