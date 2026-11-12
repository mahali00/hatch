package dev.mahali.hatch.core

@JvmInline public value class HostId(public val value: String)

/** A Rex session, for example `session:01a1c183-…`. */
@JvmInline public value class SessionId(public val value: String)

/** A window inside a session. The app calls these tabs. */
@JvmInline public value class WindowId(public val value: String)

/** A block inside a window. The app calls these panes. */
@JvmInline public value class BlockId(public val value: String)
