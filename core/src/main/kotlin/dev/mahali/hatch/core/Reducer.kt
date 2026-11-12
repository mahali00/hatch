package dev.mahali.hatch.core

public data class Reduced(val state: HostState, val effects: List<Effect> = emptyList())

/** Pure: no I/O, no clock. Events may repeat or arrive late, so every branch is idempotent. */
public fun HostState.reduce(event: HostEvent): Reduced = when (event) {
    is HostEvent.LinkChanged -> Reduced(copy(link = event.link))
    is HostEvent.HostEdited -> Reduced(copy(host = event.host))
    is HostEvent.Bootstrapped -> bootstrapped(event)
    is HostEvent.ViewLoaded -> viewLoaded(event.view)
    is HostEvent.ViewChanged -> {
        val known = sessionsById[event.id]?.revision ?: -1
        Reduced(this, if (event.revision > known) listOf(Effect.LoadView(event.id)) else emptyList())
    }
    is HostEvent.SessionCreated ->
        if (event.id in sessionsById) Reduced(this)
        else Reduced(
            copy(sessions = sessions + event.id, sessionsById = sessionsById + (event.id to Session(event.id, event.label))),
            listOf(Effect.LoadView(event.id)),
        )
    is HostEvent.SessionDestroyed -> Reduced(withoutSession(event.id))
    is HostEvent.SessionMoved -> Reduced(copy(sessions = sessions.moved(event.id, event.before)))
    is HostEvent.SessionLabelChanged -> Reduced(updateSession(event.id) { it.copy(label = event.label) })
    is HostEvent.ActiveWindowChanged -> Reduced(updateSession(event.session) { it.copy(activeWindow = event.window) })
    is HostEvent.WindowLabelChanged -> Reduced(updateWindow(event.window) { it.copy(label = event.label) })
    is HostEvent.BlockOwnerChanged -> Reduced(updateBlock(event.block) { it.copy(owner = event.owner) })
    is HostEvent.BlockClosed -> Reduced(copy(blocksById = blocksById - event.block))
    is HostEvent.TitleChanged -> Reduced(updateBlock(event.block) { it.copy(title = event.title) })
    is HostEvent.PwdChanged -> Reduced(updateBlock(event.block) { it.copy(workingDirectory = pathOf(event.pwd)) })
    is HostEvent.ProcessChanged ->
        Reduced(updateBlock(event.block) { it.copy(processName = event.name).withProgram() })
    is HostEvent.StatusChanged ->
        Reduced(updateBlock(event.block) { it.copy(status = it.status + (event.record.id to event.record)).withProgram() })
    is HostEvent.StatusLoaded ->
        Reduced(updateBlock(event.block) { b -> b.copy(status = event.records.associateBy { it.id }).withProgram() })
    is HostEvent.StatusRemoved -> Reduced(updateBlock(event.block) { it.copy(status = it.status - event.ids.toSet()).withProgram() })
    is HostEvent.ProgressChanged -> Reduced(updateBlock(event.block) { it.copy(progress = event.progress) })
    is HostEvent.ChildExited -> Reduced(updateBlock(event.block) { b ->
        b.copy(exited = true, status = b.status.filterValues { it.state != ProgramState.Working && it.state != ProgramState.Blocked }).withProgram()
    })
}

private fun HostState.bootstrapped(event: HostEvent.Bootstrapped): Reduced {
    val replaced = serverId != null && event.serverId != null && serverId != event.serverId
    val base = if (replaced) copy(sessions = emptyList(), sessionsById = emptyMap(), windowsById = emptyMap(), blocksById = emptyMap()) else this
    val ids = event.sessions.map { it.first }
    val kept = base.sessionsById.filterKeys { it in ids }
    val sessionsById = event.sessions.associate { (id, label) -> id to (kept[id]?.copy(label = label) ?: Session(id, label)) }
    var next = base.copy(
        link = Link.Online,
        clientId = event.clientId,
        serverId = event.serverId ?: base.serverId,
        replacedServer = replaced,
        sessions = ids,
        sessionsById = sessionsById,
    )
    // Includes sessions known only from the saved state, which would otherwise stay as empty tabs.
    (base.sessions + base.sessionsById.keys).distinct().filter { it !in ids }.forEach { gone -> next = next.withoutSessionContent(gone, base) }
    return Reduced(next, ids.map { Effect.LoadView(it) })
}

private fun HostState.viewLoaded(view: SessionView): Reduced {
    val current = sessionsById[view.id]
    if (current != null && view.revision < current.revision) return Reduced(this)
    val oldWindows = current?.windows.orEmpty()
    val oldBlocks = oldWindows.flatMap { windowsById[it]?.allBlocks().orEmpty() }.toSet()
    val newWindows = view.windows.map { Window(it.id, it.label, it.layout, it.floating, it.focused) }
    val infos = view.windows.flatMap { it.blocks }
    val newBlockIds = infos.map { it.id }.toSet()
    val blocks = (blocksById - (oldBlocks - newBlockIds)) + infos.associate { info ->
        info.id to (blocksById[info.id] ?: Block(info.id)).copy(label = info.label, owner = info.owner)
    }
    val session = (current ?: Session(view.id, view.label)).copy(
        label = view.label,
        revision = view.revision,
        windows = newWindows.map { it.id },
        activeWindow = view.activeWindow,
    )
    val next = copy(
        sessions = if (view.id in sessions) sessions else sessions + view.id,
        sessionsById = sessionsById + (view.id to session),
        windowsById = (windowsById - oldWindows.toSet()) + newWindows.associateBy { it.id },
        blocksById = blocks,
    )
    val fresh = infos.filter { it.id !in blocksById }.map { Effect.LoadBlockDetails(view.id, it.id) }
    return Reduced(next, fresh)
}

public fun Window.allBlocks(): List<BlockId> = layout?.blocks().orEmpty() + floating

private fun HostState.withoutSession(id: SessionId): HostState =
    withoutSessionContent(id).copy(sessions = sessions - id, sessionsById = sessionsById - id)

private fun HostState.withoutSessionContent(id: SessionId, known: HostState = this): HostState {
    val windows = known.sessionsById[id]?.windows.orEmpty()
    val blocks = windows.flatMap { windowsById[it]?.allBlocks().orEmpty() }
    return copy(windowsById = windowsById - windows.toSet(), blocksById = blocksById - blocks.toSet())
}

private inline fun HostState.updateSession(id: SessionId, change: (Session) -> Session): HostState =
    sessionsById[id]?.let { copy(sessionsById = sessionsById + (id to change(it))) } ?: this

private inline fun HostState.updateWindow(id: WindowId, change: (Window) -> Window): HostState =
    windowsById[id]?.let { copy(windowsById = windowsById + (id to change(it))) } ?: this

private inline fun HostState.updateBlock(id: BlockId, change: (Block) -> Block): HostState =
    blocksById[id]?.let { copy(blocksById = blocksById + (id to change(it))) } ?: this

private fun List<SessionId>.moved(id: SessionId, before: SessionId?): List<SessionId> {
    if (id !in this) return this
    val rest = this - id
    val at = before?.let { rest.indexOf(it) }?.takeIf { it >= 0 } ?: rest.size
    return rest.take(at) + id + rest.drop(at)
}

/** A reported directory is a `file://host/path` URL (OSC 7) or a plain path. */
internal fun pathOf(pwd: String): String {
    if (!pwd.startsWith("file://")) return pwd
    val afterScheme = pwd.removePrefix("file://")
    val path = afterScheme.substring(afterScheme.indexOf('/').takeIf { it >= 0 } ?: afterScheme.length)
    return java.net.URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)
}

internal fun Block.withProgram(): Block {
    val reported = status.values.asSequence().map { statusApp(it) }.filter { it.isNotBlank() }
        .map(::programKey).firstOrNull { it != ProgramKey.Terminal }
    return copy(program = reported ?: programKey(processName))
}
