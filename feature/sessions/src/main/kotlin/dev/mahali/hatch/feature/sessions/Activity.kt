package dev.mahali.hatch.feature.sessions

import androidx.compose.runtime.Immutable
import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.BlockedKind
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.ProgramKey
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.StatusMark
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.allBlocks
import dev.mahali.hatch.core.programKey
import dev.mahali.hatch.core.statusApp
import dev.mahali.hatch.core.title

internal enum class ActivityGroup(val label: String, val mark: StatusMark) {
    NeedsInput("Needs input", StatusMark.Blocked),
    Working("Working", StatusMark.Working),
    Done("Done", StatusMark.Done),
    Error("Error", StatusMark.Error),
    Idle("Idle", StatusMark.None),
}

@Immutable
internal data class ActivityRow(
    val ref: WindowRef,
    val block: BlockId,
    val recordId: String,
    val program: ProgramKey,
    val title: String,
    val message: String,
    val source: String,
    val session: String,
    val group: ActivityGroup,
    val kind: BlockedKind?,
    val progress: Int?,
    val updatedAtMillis: Long?,
)

internal fun activityGroups(state: AppState, preferGenerated: Boolean, showIdle: Boolean): List<Pair<ActivityGroup, List<ActivityRow>>> {
    val rows = state.hosts.mapNotNull { state.byHost[it] }.flatMap { it.activityRows(preferGenerated) }
    return ActivityGroup.entries
        .filter { showIdle || it != ActivityGroup.Idle }
        .map { group -> group to rows.filter { it.group == group }.sortedByDescending { it.updatedAtMillis ?: Long.MIN_VALUE } }
        .filter { it.second.isNotEmpty() }
}

private fun HostState.activityRows(preferGenerated: Boolean): List<ActivityRow> = sessions.flatMap { sessionId ->
    val session = sessionsById[sessionId] ?: return@flatMap emptyList()
    session.windows.mapNotNull { windowsById[it] }.flatMap { window ->
        window.allBlocks().distinct().mapNotNull { blocksById[it] }.flatMap { pane ->
            val paneTitle = pane.title(preferGenerated).primary
            pane.status.values.map { record ->
                val app = pane.statusApp(record)
                ActivityRow(
                    ref = WindowRef(host.id, sessionId, window.id),
                    block = pane.id,
                    recordId = record.id,
                    program = if (app.isBlank()) pane.program else programKey(app),
                    title = record.title.ifBlank { record.id.ifBlank { paneTitle } },
                    message = record.message,
                    // The session, as Rex's own clients show it; the title already names the pane.
                    source = session.label,
                    session = session.label,
                    group = when (record.state) {
                        ProgramState.Blocked -> ActivityGroup.NeedsInput
                        ProgramState.Working -> ActivityGroup.Working
                        ProgramState.Done -> ActivityGroup.Done
                        ProgramState.Error -> ActivityGroup.Error
                        // A named agent that goes idle is done (Rex's clients list it under Done); a bare idle record stays Idle.
                        ProgramState.Idle -> if (app.isNotBlank()) ActivityGroup.Done else ActivityGroup.Idle
                    },
                    kind = record.kind,
                    progress = record.progress,
                    updatedAtMillis = parseInstantMillis(record.updatedAt),
                )
            }
        }
    }
}
