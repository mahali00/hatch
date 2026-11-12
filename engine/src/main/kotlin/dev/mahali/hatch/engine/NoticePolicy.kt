package dev.mahali.hatch.engine

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.BlockedKind
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.PromptFallback
import dev.mahali.hatch.core.PromptState
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.core.promptTarget
import dev.mahali.hatch.core.title
import dev.mahali.hatch.protocol.BlockEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public class NoticePolicy {
    private val _notices = MutableStateFlow<Map<PromptTarget, Notice>>(emptyMap())

    public val notices: StateFlow<Map<PromptTarget, Notice>> = _notices.asStateFlow()

    private val _activityEnabled = MutableStateFlow(false)

    public val activityEnabled: StateFlow<Boolean> = _activityEnabled.asStateFlow()

    private var state = AppState()
    private var settings = Settings()
    private var visible: WindowRef? = null
    private var focused: PromptTarget? = null
    private var entries = emptyMap<PromptTarget, PromptState>()
    private var activityAllowed = false
    private var terminalAllowed = false

    private val posted = HashMap<PromptTarget, Notice>()

    private var previous = emptyMap<PromptTarget, PromptState>()

    private val lastState = HashMap<Triple<HostId, BlockId, String>, Pair<ProgramState, BlockedKind?>>()

    // Blocked spells seen starting in this process and not yet alerted; one found on connect or restart posts silently.
    private val unalerted = HashSet<Pair<HostId, BlockId>>()

    private var posts = 0L

    @Synchronized public fun onState(state: AppState) {
        this.state = state
        // An offline host's pane list is stale, so its notices stay.
        val gone = posted.keys.filter { t ->
            val host = state.byHost[t.window.host]
            host != null && host.link == Link.Online && t.block !in host.blocksById
        }
        if (gone.isNotEmpty()) { gone.forEach(posted::remove); publish() }
    }

    @Synchronized public fun onSettings(settings: Settings) {
        this.settings = settings
        reconcilePrompts()
    }

    @Synchronized public fun onScreen(visible: WindowRef?, focused: PromptTarget?) {
        this.visible = visible
        this.focused = focused
        if (focused != null && focused.window == visible) posted.remove(focused)
        publish()
    }

    @Synchronized public fun onPrompts(entries: Map<PromptTarget, PromptState>) {
        this.entries = entries
        reconcilePrompts()
    }

    @Synchronized public fun onDeliveryAllowed(activity: Boolean, terminal: Boolean) {
        activityAllowed = activity
        terminalAllowed = terminal
        reconcilePrompts()
    }

    @Synchronized public fun onEvent(host: HostId, event: BlockEvent) {
        if (event is BlockEvent.StatusRemoved) {
            lastState.keys.removeAll { (h, block, id) -> h == host && block == event.block && id in event.ids }
            if ("" in event.ids) unalerted.remove(host to event.block)
            return
        }
        if (!settings.notifications) return
        when (event) {
            is BlockEvent.StatusChanged -> {
                if (!settings.notifyProgramActivity) return
                val record = event.record
                // Child-task reports don't mean the agent finished, so they don't notify.
                if (record.id.isNotEmpty()) return
                // Notify on entering a state: a busy agent re-reports constantly.
                val now = record.state to record.kind
                if (lastState.put(Triple(host, event.block, record.id), now) == now) {
                    // Same state again: only a changed message or title updates the shown notice, quietly.
                    val shown = target(host, event.session, event.block)?.let(posted::get) ?: return
                    if ((record.message.isBlank() || record.message == shown.text) && (record.title.isBlank() || record.title == shown.title)) return
                } else if (record.state == ProgramState.Blocked) unalerted.add(host to event.block)
                else unalerted.remove(host to event.block)
                if (settings.alertFor(record.state) == Settings.Alert.Off) {
                    state.promptTarget(host, event.session, event.block)?.let(posted::remove)
                    return publish()
                }
                val fallback = when (record.state) {
                    ProgramState.Done -> "Finished"
                    ProgramState.Error -> "Failed"
                    ProgramState.Blocked -> record.kind?.label ?: "Needs your input"
                    else -> {
                        state.promptTarget(host, event.session, event.block)?.let(posted::remove)
                        return publish()
                    }
                }
                post(host, event.session, event.block, Notice.Kind.Activity, record.title, record.message.ifBlank { fallback }, record.state)
            }
            is BlockEvent.DesktopNotification -> {
                if (!settings.notifyTerminal) return
                post(host, event.session, event.block, Notice.Kind.Terminal, event.title, event.body)
            }
            else -> return
        }
        publish()
    }

    private fun reconcilePrompts() {
        val enabled = settings.notifications && settings.notifyProgramActivity && activityAllowed
        _activityEnabled.value = enabled
        if (!enabled) {
            previous = emptyMap()
            posted.values.removeAll { it.kind == Notice.Kind.Activity }
            return publish()
        }
        val before = previous
        previous = entries
        for (target in before.keys - entries.keys) {
            if (posted[target]?.state == ProgramState.Blocked) posted.remove(target)
        }
        for ((target, prompt) in entries) {
            if (before[target] == prompt) continue
            val record = state.byHost[target.window.host]?.blocksById?.get(target.block)?.status?.get("") ?: continue
            if (record.state != ProgramState.Blocked || settings.alertBlocked == Settings.Alert.Off) continue
            post(target.window.host, target.window.session, target.block, Notice.Kind.Activity, record.title,
                record.message.ifBlank { record.kind?.label ?: "Needs your input" }, ProgramState.Blocked, repost = true)
        }
        publish()
    }

    // The reported session first; scan the host's other sessions only when the pane is not there.
    private fun target(host: HostId, session: SessionId, block: BlockId): PromptTarget? =
        state.promptTarget(host, session, block) ?: state.byHost[host]?.sessions?.firstNotNullOfOrNull { state.promptTarget(host, it, block) }

    private fun post(host: HostId, session: SessionId, block: BlockId, kind: Notice.Kind, title: String?, text: String, programState: ProgramState? = null, repost: Boolean = false) {
        val hostState = state.byHost[host] ?: return
        val target = target(host, session, block) ?: return
        val alert = programState != ProgramState.Blocked || unalerted.remove(host to block)
        if (visible == target.window && focused == target) return
        if (!(if (kind == Notice.Kind.Activity) activityAllowed else terminalAllowed)) return
        val tabTitle = hostState.windowsById[target.window.window]?.let { hostState.title(it).primary }
        val session = hostState.sessionsById[target.window.session]?.label.orEmpty()
        val previous = posted[target]
        val quiet = !alert || repost && previous != null || programState != null && previous?.state == programState
        val silent = !alert || programState?.let { settings.alertFor(it) } == Settings.Alert.Silent
        posted[target] = Notice(target, kind, title?.takeIf { it.isNotBlank() } ?: tabTitle ?: session, text, programState, quiet = quiet, silent = silent, serial = ++posts)
    }

    private fun publish() {
        _notices.value = posted.mapValues { (target, notice) -> present(notice, entries[target]) }
    }

    private fun present(notice: Notice, prompt: PromptState?): Notice {
        if (notice.state != ProgramState.Blocked) return notice
        return when (prompt) {
            is PromptState.Offered -> notice.copy(text = prompt.view.question, detail = prompt.view.context.takeIf { it.isNotBlank() },
                // Android shows at most three action buttons; more are answered in the app.
                choices = prompt.view.choices.takeIf { it.all.size <= 3 })
            is PromptState.Checking -> notice.copy(text = "Checking the current prompt…")
            is PromptState.Sending -> notice.copy(text = "Sending your answer…")
            is PromptState.AwaitingChange -> notice.copy(text = "Answer sent. Checking whether the prompt closed.")
            is PromptState.OpenOnly -> when (prompt.reason) {
                PromptFallback.Unconfirmed -> notice.copy(text = "Couldn't confirm your answer. Open the terminal to check.")
                PromptFallback.Expired, PromptFallback.Changed -> notice.copy(text = "Open the terminal to review the current request.")
                else -> notice
            }
            null -> notice
        }
    }

    public companion object {
        /** A program on a pane this device owns may change this phone: copy text, open links. */
        public fun owns(state: AppState, host: HostId, block: BlockId): Boolean {
            val hostState = state.byHost[host] ?: return false
            return hostState.clientId != null && hostState.blocksById[block]?.owner == hostState.clientId
        }

        public fun mayWriteClipboard(state: AppState, host: HostId, block: BlockId, text: String): Boolean =
            owns(state, host, block) && text.isNotEmpty() && text.length <= MAX_CLIPBOARD_CHARS

        /** OSC 52 copies over 1 MiB are ignored; no real selection is that large. */
        private const val MAX_CLIPBOARD_CHARS = 1 shl 20
    }
}
