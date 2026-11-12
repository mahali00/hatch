package dev.mahali.hatch.engine

import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.PromptChoices
import dev.mahali.hatch.core.PromptTarget

/** Decided by NoticePolicy; Android delivery posts, updates and removes these and decides nothing. */
public data class Notice(
    val target: PromptTarget,
    val kind: Kind,
    val title: String?,
    val text: String,
    val state: ProgramState? = null,
    val choices: PromptChoices? = null,
    val quiet: Boolean = false,
    val silent: Boolean = false,
    val detail: String? = null,
    /** Counts posts: delivery re-posts whenever this changes. */
    val serial: Long = 0,
) {
    public enum class Kind {
        Activity,
        Terminal,
    }
}
