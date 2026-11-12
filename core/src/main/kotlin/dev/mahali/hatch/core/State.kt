package dev.mahali.hatch.core

/** Pairing stores only the address, never a credential. */
public data class Host(
    val id: HostId,
    val name: String,
    val url: String,
    val kind: HostKind = HostKind.MacBook,
    val startupCommand: String = "",
    /** A paused host stays saved but is not connected. */
    val enabled: Boolean = true,
)

public enum class HostKind(public val label: String) {
    Globe("Globe"), Server("Server"), Cloud("Cloud"), MacBook("MacBook"),
    MacMini("Mac mini"), MacStudio("Mac Studio"), Desktop("Desktop");

    public companion object {
        public fun fromStored(value: String?): HostKind = when (value) {
            "Laptop" -> MacBook
            else -> entries.firstOrNull { it.name == value } ?: MacBook
        }
    }
}

public sealed interface Link {
    public data object Online : Link

    public data class Connecting(val retryAtMillis: Long? = null, val lastError: String? = null) : Link

    public data class Offline(val reason: String, val retryAtMillis: Long? = null) : Link
}

public data class StatusRecord(
    val id: String,
    val state: ProgramState,
    val title: String = "",
    val message: String = "",
    val progress: Int? = null,
    val updatedAt: String = "",
    val app: String = "",
    val kind: BlockedKind? = null,
    val effectiveApp: String? = null,
)

public enum class BlockedKind(public val label: String) {
    Permission("Approval needed"), Question("Answer needed"), Auth("Sign-in needed");

    public companion object {
        public fun parse(value: String?): BlockedKind? = when (value) {
            "permission" -> Permission
            "question" -> Question
            "auth" -> Auth
            else -> null
        }
    }
}

public enum class ProgramState {
    Idle, Working, Done, Blocked, Error;

    public companion object {
        public fun parse(value: String?): ProgramState? = when (value) {
            "idle" -> Idle
            "working" -> Working
            "done" -> Done
            "blocked" -> Blocked
            "error" -> Error
            else -> null
        }
    }
}

public data class Block(
    val id: BlockId,
    val label: String = "",
    val title: String = "",
    val workingDirectory: String = "",
    val processName: String = "",
    val program: ProgramKey = ProgramKey.Terminal,
    val owner: String? = null,
    val status: Map<String, StatusRecord> = emptyMap(),
    /** OSC 9;4 progress from 0 to 100, or null when none is shown. */
    val progress: Int? = null,
    val exited: Boolean = false,
)

public data class Window(
    val id: WindowId,
    val label: String,
    val layout: Layout?,
    val floating: List<BlockId> = emptyList(),
    val focused: BlockId? = null,
)

/** [revision] increases with every change to the session's view, so stale views can be dropped. */
public data class Session(
    val id: SessionId,
    val label: String,
    val revision: Long = -1,
    val windows: List<WindowId> = emptyList(),
    val activeWindow: WindowId? = null,
)

public data class HostState(
    val host: Host,
    val link: Link = Link.Connecting(),
    val clientId: String? = null,
    /** The server's `host_id`. A different value after reconnecting means a different server. */
    val serverId: String? = null,
    val replacedServer: Boolean = false,
    val sessions: List<SessionId> = emptyList(),
    val sessionsById: Map<SessionId, Session> = emptyMap(),
    val windowsById: Map<WindowId, Window> = emptyMap(),
    val blocksById: Map<BlockId, Block> = emptyMap(),
)

/** The whole app. Owned by one reducer; everyone else reads immutable snapshots. */
public data class AppState(
    val hosts: List<HostId> = emptyList(),
    val byHost: Map<HostId, HostState> = emptyMap(),
)
