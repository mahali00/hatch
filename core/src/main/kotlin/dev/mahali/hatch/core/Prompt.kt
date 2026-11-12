package dev.mahali.hatch.core

public data class PromptTarget(public val window: WindowRef, public val block: BlockId)

public fun PromptTarget.existsIn(state: AppState): Boolean {
    val host = state.byHost[window.host] ?: return false
    if (window.window !in host.sessionsById[window.session]?.windows.orEmpty()) return false
    return host.windowsById[window.window]?.allBlocks()?.contains(block) == true
}

/** A process-local choice ID. It is never a terminal key or an agent request ID. */
@JvmInline public value class PromptActionId(public val value: String)

public data class PromptChoice(public val label: String, public val action: PromptActionId)
public data class PromptChoices(public val first: PromptChoice, public val rest: List<PromptChoice>) {
    public val all: List<PromptChoice> get() = listOf(first) + rest
}
public data class PromptView(public val question: String, public val context: String, public val choices: PromptChoices)

public enum class PromptFallback {
    Offline, Unsupported, Incomplete, TooLarge, Changed, Expired, InputBusy, AlreadyAnswered, Unconfirmed,
}

public sealed interface PromptState {
    public data class Offered(public val view: PromptView) : PromptState
    public data class Checking(public val view: PromptView) : PromptState
    public data class Sending(public val view: PromptView) : PromptState
    public data class AwaitingChange(public val view: PromptView) : PromptState
    public data class OpenOnly(public val reason: PromptFallback) : PromptState
}

public sealed interface AnswerOutcome {
    public data class NotSent(public val reason: PromptFallback) : AnswerOutcome
    public data object InputAcknowledged : AnswerOutcome
    public data object DeliveryUnconfirmed : AnswerOutcome
}

/** Resolves the exact current location. It never substitutes another pane. */
public fun AppState.promptTarget(host: HostId, session: SessionId, block: BlockId): PromptTarget? {
    val state = byHost[host] ?: return null
    val current = state.sessionsById[session] ?: return null
    val window = current.windows.firstOrNull { id -> state.windowsById[id]?.allBlocks()?.contains(block) == true } ?: return null
    return PromptTarget(WindowRef(host, session, window), block)
}
