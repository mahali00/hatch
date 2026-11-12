package dev.mahali.hatch.core

public data class Title(val primary: String, val secondary: String = "")

private val shells = setOf("bash", "zsh", "fish", "nu", "nushell", "sh", "dash", "ksh", "tcsh", "pwsh")
private val defaultWindowLabel = Regex("Window [0-9a-f]{8}")

public fun isShell(processName: String): Boolean = processName.removePrefix("-").lowercase() in shells

public fun Block.title(preferGenerated: Boolean = false): Title {
    val process = processName.removePrefix("-")
    val dir = abbreviateHome(workingDirectory)
    return when {
        preferGenerated && process.isNotEmpty() && isShell(process) -> Title(process, dir)
        title.isNotBlank() -> Title(title)
        process.isNotEmpty() -> Title(process, dir)
        label.isNotBlank() -> Title(label)
        else -> Title("Terminal")
    }
}

public fun HostState.title(window: Window, preferGenerated: Boolean = false): Title {
    if (window.label.isNotBlank() && !defaultWindowLabel.matches(window.label)) return Title(window.label)
    val pane = focusedBlock(window)
    return pane?.title(preferGenerated) ?: Title(window.label.ifBlank { "Terminal" })
}

public fun abbreviateHome(path: String): String {
    val match = Regex("^/(Users|home)/[^/]+").find(path) ?: return path
    return "~" + path.substring(match.range.last + 1)
}

public enum class StatusMark { None, Done, Working, Blocked, Error }

public val ProgramState.mark: StatusMark
    get() = when (this) {
        ProgramState.Idle -> StatusMark.None
        ProgramState.Working -> StatusMark.Working
        ProgramState.Done -> StatusMark.Done
        ProgramState.Blocked -> StatusMark.Blocked
        ProgramState.Error -> StatusMark.Error
    }

public fun Block.statusMark(): StatusMark = status.values.maxOfOrNull { it.state.mark } ?: StatusMark.None

/** An indeterminate OSC 7501 record must not borrow an old OSC 9;4 percentage. */
public fun Block.statusProgress(): Int? = if (status.isEmpty()) progress else {
    val mark = statusMark()
    status.values.filter { it.state.mark == mark }.mapNotNull { it.progress }.maxOrNull()
}

public fun Block.statusApp(record: StatusRecord): String {
    if (record.app.isNotBlank()) return record.app
    var id = record.id
    while (id.isNotEmpty()) {
        id = id.substringBeforeLast('/', "")
        status[id]?.app?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return record.effectiveApp.orEmpty()
}

public fun HostState.statusMark(window: Window): StatusMark =
    window.allBlocks().mapNotNull { blocksById[it]?.statusMark() }.maxOrNull() ?: StatusMark.None

public fun HostState.focusedBlock(window: Window): Block? =
    (window.focused ?: window.allBlocks().firstOrNull())?.let { blocksById[it] }
