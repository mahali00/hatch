package dev.mahali.hatch.core

/** A session's view as `session.view` reports it, already decoded. */
public data class SessionView(
    val id: SessionId,
    val label: String,
    val revision: Long,
    val activeWindow: WindowId?,
    val windows: List<WindowView>,
)

public data class WindowView(
    val id: WindowId,
    val label: String,
    val layout: Layout?,
    val floating: List<BlockId>,
    val focused: BlockId?,
    val blocks: List<BlockInfo>,
)

public data class BlockInfo(val id: BlockId, val label: String, val owner: String?)

/** Changes to a HostState, in app terms; :protocol decodes the wire into these. */
public sealed interface HostEvent {
    public data class LinkChanged(val link: Link) : HostEvent

    public data class HostEdited(val host: Host) : HostEvent

    /** The result of `session.bootstrap`: every session, plus the server's identity. */
    public data class Bootstrapped(
        val clientId: String,
        val serverId: String?,
        val sessions: List<Pair<SessionId, String>>,
    ) : HostEvent

    public data class ViewLoaded(val view: SessionView) : HostEvent
    public data class SessionCreated(val id: SessionId, val label: String) : HostEvent
    public data class SessionDestroyed(val id: SessionId) : HostEvent

    /** [before] is the session that now follows [id], or null when [id] is last. */
    public data class SessionMoved(val id: SessionId, val before: SessionId?) : HostEvent
    public data class SessionLabelChanged(val id: SessionId, val label: String) : HostEvent
    public data class ViewChanged(val id: SessionId, val revision: Long) : HostEvent
    public data class ActiveWindowChanged(val session: SessionId, val window: WindowId?) : HostEvent
    public data class WindowLabelChanged(val session: SessionId, val window: WindowId, val label: String) : HostEvent
    public data class BlockOwnerChanged(val block: BlockId, val owner: String?) : HostEvent
    public data class BlockClosed(val block: BlockId) : HostEvent
    public data class TitleChanged(val block: BlockId, val title: String) : HostEvent
    public data class PwdChanged(val block: BlockId, val pwd: String) : HostEvent
    public data class ProcessChanged(val block: BlockId, val name: String) : HostEvent
    public data class StatusChanged(val block: BlockId, val record: StatusRecord) : HostEvent

    /** Replaces every record of [block], as the `program_status` method reports them. */
    public data class StatusLoaded(val block: BlockId, val records: List<StatusRecord>) : HostEvent
    public data class StatusRemoved(val block: BlockId, val ids: List<String>) : HostEvent
    public data class ProgressChanged(val block: BlockId, val progress: Int?) : HostEvent
    public data class ChildExited(val block: BlockId) : HostEvent
}

public sealed interface Effect {
    /** Call `session.view`: the server reported a revision newer than the one held. */
    public data class LoadView(val session: SessionId) : Effect

    /** Call `title`, `pwd`, `process` and `program_status` for a block seen for the first time. */
    public data class LoadBlockDetails(val session: SessionId, val block: BlockId) : Effect
}
