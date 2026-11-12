package dev.mahali.hatch.feature.sessions

import androidx.compose.runtime.Immutable
import dev.mahali.hatch.core.focusedBlock
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.StatusMark
import dev.mahali.hatch.core.BlockedKind
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.allBlocks
import dev.mahali.hatch.core.statusMark
import dev.mahali.hatch.core.statusProgress
import dev.mahali.hatch.core.title

@Immutable
internal data class TabRowModel(
    val ref: WindowRef,
    val title: String,
    val detail: String,
    val program: ProgramKey,
    val stacked: Boolean,
    val mark: StatusMark,
    val progress: Int?,
    val kind: BlockedKind?,
)

internal fun HostState.tabRow(session: SessionId, window: Window, preferGenerated: Boolean): TabRowModel {
    val title = title(window, preferGenerated)
    val pane = focusedBlock(window)
    val blocks = window.allBlocks()
    val mark = statusMark(window)
    val marked = blocks.mapNotNull { blocksById[it] }.filter { it.statusMark() == mark }
    return TabRowModel(
        ref = WindowRef(host.id, session, window.id),
        title = title.primary,
        detail = title.secondary,
        program = pane?.program ?: ProgramKey.Terminal,
        stacked = blocks.size > 1,
        mark = mark,
        progress = marked.mapNotNull { it.statusProgress() }.maxOrNull(),
        kind = marked.flatMap { it.status.values }.filter { it.state == ProgramState.Blocked }
            .map { it.kind }.distinct().singleOrNull(),
    )
}

internal fun HostState.tabRows(session: Session, preferGenerated: Boolean): List<TabRowModel> =
    session.windows.mapNotNull { windowsById[it] }.map { tabRow(session.id, it, preferGenerated) }
