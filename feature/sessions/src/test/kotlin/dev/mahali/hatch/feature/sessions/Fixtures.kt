package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.Block
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.programKey


internal val mac = HostId("host:mac")

internal fun pane(id: String, process: String, dir: String = "", title: String = "") =
    Block(BlockId(id), title = title, workingDirectory = dir, processName = process, program = programKey(process))

internal fun tab(id: String, vararg panes: Block): Window {
    val layout = panes.map<Block, Layout> { Layout.Leaf(it.id) }.reduce { a, b -> Layout.Split(Axis.Row, 0.5f, a, b) }
    return Window(WindowId(id), label = "", layout = layout, focused = panes.first().id)
}

internal fun host(vararg sessions: Pair<Session, List<Window>>, link: Link = Link.Online): HostState {
    val windows = sessions.flatMap { it.second }
    return HostState(
        host = Host(mac, "MacBook Pro", "ws://mac"),
        link = link,
        sessions = sessions.map { it.first.id },
        sessionsById = sessions.associate { (s, w) -> s.id to s.copy(windows = w.map { it.id }, revision = 1) },
        windowsById = windows.associateBy { it.id },
        blocksById = emptyMap(),
    )
}

internal fun HostState.withBlocks(vararg blocks: Block) = copy(blocksById = blocksById + blocks.associateBy { it.id })

internal fun session(id: String, label: String) = Session(SessionId(id), label)

internal fun HostState.app() = AppState(hosts = listOf(host.id), byHost = mapOf(host.id to this))
