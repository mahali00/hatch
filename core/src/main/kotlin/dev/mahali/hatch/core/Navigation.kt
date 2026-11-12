package dev.mahali.hatch.core

public fun HostState.sessionTarget(id: SessionId): WindowRef? {
    val session = sessionsById[id] ?: return null
    val active = session.activeWindow?.takeIf { it in session.windows && it in windowsById }
    val window = active ?: session.windows.firstOrNull { it in windowsById } ?: return null
    return WindowRef(host.id, id, window)
}

public fun AppState.hasTab(ref: WindowRef): Boolean = byHost[ref.host]?.let { host ->
    ref.window in host.windowsById && host.sessionsById[ref.session]?.windows?.contains(ref.window) == true
} == true

/** The most recent still-open tab in [recent] (same session, then same host), else the closed tab's neighbour. */
public fun AppState.afterTabClosed(current: WindowRef?, closed: WindowRef, rememberedIndex: Int = 0, recent: List<WindowRef> = emptyList()): WindowRef? {
    if (current != closed) return current
    val open = recent.filter { it != closed && it.host == closed.host && hasTab(it) }
    (open.firstOrNull { it.session == closed.session } ?: open.firstOrNull())?.let { return it }
    val host = byHost[closed.host] ?: return null
    val windows = host.sessionsById[closed.session]?.windows.orEmpty()
    val at = windows.indexOf(closed.window)
    val others = windows.filter { it != closed.window && it in host.windowsById }
    val index = if (at >= 0) windows.take(at).count { it in host.windowsById } else rememberedIndex.coerceAtLeast(0)
    val next = others.getOrNull(index) ?: others.lastOrNull()
    return next?.let { closed.copy(window = it) }
}
