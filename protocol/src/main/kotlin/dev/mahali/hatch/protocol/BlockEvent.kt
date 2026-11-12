package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.StatusRecord

/** A pane's server event, decoded once; nothing above the protocol module reads raw JSON. */
public sealed interface BlockEvent {
    public val session: SessionId
    public val block: BlockId

    /** [record]'s id is empty for the program itself, set for a child task. */
    public data class StatusChanged(override val session: SessionId, override val block: BlockId, val record: StatusRecord) : BlockEvent

    public data class StatusRemoved(override val session: SessionId, override val block: BlockId, val ids: Set<String>) : BlockEvent

    /** OSC 9 or OSC 777. */
    public data class DesktopNotification(override val session: SessionId, override val block: BlockId, val title: String?, val body: String) : BlockEvent

    /** OSC 52. Large text arrives as [id], read with `clipboardRead`. */
    public data class ClipboardWritten(
        override val session: SessionId,
        override val block: BlockId,
        val text: String?,
        val id: Long?,
        val bytes: Long,
    ) : BlockEvent

    public data class UrlClicked(override val session: SessionId, override val block: BlockId, val url: String) : BlockEvent

    public data class Bell(override val session: SessionId, override val block: BlockId) : BlockEvent
}
