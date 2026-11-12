package dev.mahali.hatch.engine

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.GridSize
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.protocol.KeyEvent

/** Sent to a host through one ordered queue. Input is never dropped quietly; Control is coalesced, latest resize wins. */
public sealed interface Command {
    public val session: SessionId

    public sealed interface Input : Command {
        public val block: BlockId

        public data class Key(override val session: SessionId, override val block: BlockId, val key: KeyEvent) : Input
        public data class Text(override val session: SessionId, override val block: BlockId, val text: String, val mods: Mods = Mods()) : Input
        public data class Paste(override val session: SessionId, override val block: BlockId, val text: String) : Input

        /** [lines] wheel steps at a cell; positive scrolls down. */
        public data class Wheel(override val session: SessionId, override val block: BlockId, val column: Int, val row: Int, val lines: Int) : Input

        /** Press then release at a cell; columns and rows count from 0 at the top left. */
        public data class Click(override val session: SessionId, override val block: BlockId, val column: Int, val row: Int) : Input
    }

    public sealed interface Control : Command {
        public data class Claim(override val session: SessionId, val sizes: Map<BlockId, GridSize>, val colors: TerminalColors) : Control

        public data class Resize(override val session: SessionId, val sizes: Map<BlockId, GridSize>, val colors: TerminalColors? = null) : Control

        public data class Release(override val session: SessionId, val blocks: Set<BlockId>) : Control

        public data class Select(override val session: SessionId, val block: BlockId) : Control

        public data class Focus(override val session: SessionId, val block: BlockId, val focused: Boolean) : Control
    }
}
