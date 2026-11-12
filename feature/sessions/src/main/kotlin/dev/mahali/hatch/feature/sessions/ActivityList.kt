package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.StatusGlyph
import dev.mahali.hatch.design.LocalListMetrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.ProgramTile
import dev.mahali.hatch.design.HatchListCard
import dev.mahali.hatch.design.HatchListDivider
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome

internal fun LazyListScope.activityItems(
    groups: List<Pair<ActivityGroup, List<ActivityRow>>>,
    collapsed: List<ActivityGroup>,
    nowMillis: Long,
    onToggle: (ActivityGroup) -> Unit,
    onOpenPane: (WindowRef, BlockId) -> Unit,
) {
    item(key = "activity-top", contentType = "spacer") { Spacer(Modifier.height(Dimens.ScreenInset)) }
    if (groups.isEmpty()) {
        item(key = "activity-empty") {
            Text(
                "No processes are reporting activity", Modifier.padding(horizontal = Dimens.SectionHeaderStart, vertical = Hatch.space.m),
                style = HatchType.Callout, color = chrome.secondary,
            )
        }
        return
    }
    groups.forEach { (group, rows) ->
        val isCollapsed = group in collapsed
        item(key = "group:${group.name}", contentType = "group") {
            GroupRow(group.label, isCollapsed, { onToggle(group) }, Modifier.semantics { heading() })
        }
        if (!isCollapsed) item(key = "rows:${group.name}", contentType = "rows") {
            HatchListCard(Modifier.padding(bottom = CardGap)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HatchListDivider()
                    ActivityRowView(row, nowMillis) { onOpenPane(row.ref, row.block) }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ActivityRowView(row: ActivityRow, nowMillis: Long, onClick: () -> Unit) {
    HatchListRow(
        Modifier.heightIn(min = Dimens.TwoLineRowHeight),
        onClick = onClick,
        leading = { ProgramTile(row.program, LocalListMetrics.current.leading) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusGlyph(row.group.mark, row.progress, showDone = false, kind = row.kind)
                row.updatedAtMillis?.let { Text(formatAge(it, nowMillis), style = HatchType.Footnote, color = chrome.secondary) }
            }
        },
    ) {
        Text(row.title, style = LocalListMetrics.current.body, color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(row.kind?.label, row.progress?.let { "$it%" }, row.message.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, style = HatchType.Callout,
            color = if (row.group == ActivityGroup.NeedsInput) chrome.warning else chrome.secondary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(row.source, style = HatchType.Footnote, color = chrome.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
