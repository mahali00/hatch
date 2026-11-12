package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.ButtonSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Title
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.ProgramTile
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome

@Composable
internal fun TerminalHeader(
    program: ProgramKey,
    title: Title,
    session: String,
    onBack: (() -> Unit)?,
    onSession: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit,
) {
    val horizontal = if (onBack == null) 8.dp else Dimens.SidebarToggleStart
    val padded = modifier.fillMaxWidth()
        .padding(start = horizontal, end = horizontal, top = Dimens.SidebarToggleTop, bottom = HeaderBottom)
    if (onBack != null) {
        Row(padded, verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(HatchIcons.ChevronLeft, "Back", onBack)
            TitleBlock(program, title, session, onSession, Modifier.weight(1f).padding(start = Hatch.space.xs, end = Hatch.space.s))
            MoreButton(onMenu, menu)
        }
    } else {
        Box(padded) {
            TitleBlock(program, title, session, onSession, Modifier.align(Alignment.Center).padding(horizontal = Dimens.TouchTarget + Hatch.space.s), compact = true)
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                MoreButton(onMenu, menu, compact = true)
            }
        }
    }
}

private val HeaderBottom = 6.dp

@Composable
private fun MoreButton(onMenu: () -> Unit, menu: @Composable () -> Unit, compact: Boolean = false) {
    Box {
        HeaderButton(HatchIcons.MoreHorizontal, "More", onMenu, size = if (compact) ButtonSize.Toolbar else ButtonSize.Standard)
        menu()
    }
}

@Composable
private fun TitleBlock(program: ProgramKey, title: Title, session: String, onSession: () -> Unit, modifier: Modifier, compact: Boolean = false) {
    Row(
        modifier.clickable(onClickLabel = "Switch tab or session", onClick = onSession),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) Hatch.space.s else Dimens.IconGap),
    ) {
        ProgramTile(program, if (compact) 24.dp else Dimens.LeadingWidth)
        Column {
            Text(
                buildAnnotatedString {
                    append(title.primary)
                    if (title.secondary.isNotEmpty()) withStyle(SpanStyle(color = chrome.secondary, fontWeight = FontWeight.Normal)) { append(" ${title.secondary}") }
                },
                style = HatchType.Title, color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Always drawn, so the header never changes height while the session loads.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hatch.space.xxs)) {
                Text(
                    session, Modifier.weight(1f, fill = false), style = HatchType.Footnote, color = chrome.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(HatchIcons.ChevronDown, null, Modifier.size(Dimens.SmallChevron), tint = chrome.secondary)
            }
        }
    }
}

internal fun sessionLine(label: String?, id: SessionId): String =
    label?.takeIf { it.isNotBlank() } ?: id.value.takeLast(6).ifBlank { "Session" }
