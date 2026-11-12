package dev.mahali.hatch.core

import kotlinx.coroutines.flow.StateFlow

// Feature modules depend only on these seams; the app implements them.

public data class WindowRef(val host: HostId, val session: SessionId, val window: WindowId)

public interface HostStore {
    public val hosts: StateFlow<List<Host>>

    public fun save(host: Host)
    public fun remove(id: HostId)
    public fun reorder(ids: List<HostId>)
}

public interface SettingsStore {
    public val settings: StateFlow<Settings>
    public fun update(change: (Settings) -> Settings)
}

public interface RexActions {
    public fun newSession(host: HostId)
    public fun newTab(host: HostId, session: SessionId)
    public fun renameSession(host: HostId, session: SessionId, label: String)
    public fun closeSession(host: HostId, session: SessionId)
    public fun renameTab(ref: WindowRef, label: String)
    public fun closeTab(ref: WindowRef)

    public fun moveTab(ref: WindowRef, forward: Boolean)
    /** Puts the tab just before [before] in its session, or last when null: one move_window, wherever it lands. */
    public fun placeTab(ref: WindowRef, before: WindowId?)
    public fun reconnect(host: HostId)
    public fun setClientLabel(label: String)
}

public sealed interface ProbeResult {
    public data class Reached(val url: String, val suggestedName: String?) : ProbeResult
    public data class Failed(val message: String) : ProbeResult
}

public fun interface HostProbe {
    public suspend fun probe(url: String): ProbeResult
}
