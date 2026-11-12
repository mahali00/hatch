package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchPill
import dev.mahali.hatch.design.HatchPillButton
import dev.mahali.hatch.design.HatchPillText
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.floatingEdge
import java.util.Locale

// Presentation only: Uploads owns preparation, transport, progress and cleanup.
@Composable
internal fun UploadStatus(pill: UploadPill, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    when (pill) {
        is UploadPill.Sending -> UploadProgress(pill, modifier)
        is UploadPill.Failed -> HatchPill(modifier, end = 10.dp) {
            Icon(HatchIcons.Alert, null, Modifier.size(Dimens.Icon), tint = chrome.accent)
            HatchPillText("Upload failed · ${pill.reason}", chrome.text)
            HatchPillButton("Dismiss", onDismiss, chrome.text, background = chrome.stroke)
        }
    }
}

@Composable
internal fun UploadProgress(pill: UploadPill.Sending, modifier: Modifier) {
    Column(
        modifier.width(300.dp).clip(HatchShapes.Card).background(chrome.card).floatingEdge(HatchShapes.Card)
            .padding(horizontal = Dimens.ScreenInset, vertical = Hatch.space.m),
        verticalArrangement = Arrangement.spacedBy(Hatch.space.s),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.IconGap),
        ) {
            Box(
                Modifier.size(36.dp).clip(HatchShapes.Small).background(chrome.stroke),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HatchIcons.Picture, null, Modifier.size(Dimens.Icon), tint = chrome.text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Hatch.space.xxs)) {
                Text(
                    pill.label, style = HatchType.Callout, color = chrome.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (pill.total > 0) {
                    Text(
                        "${megabytes(pill.sent)} of ${megabytes(pill.total)}",
                        style = Hatch.type.mono,
                        color = chrome.secondary, maxLines = 1,
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { if (pill.total > 0) pill.sent.toFloat() / pill.total else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = chrome.accent,
            trackColor = chrome.stroke,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

internal fun megabytes(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
