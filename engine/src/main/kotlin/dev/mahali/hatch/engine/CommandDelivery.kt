package dev.mahali.hatch.engine

import dev.mahali.hatch.core.HostFailure
import kotlinx.coroutines.Deferred

public sealed interface CommandSubmission {
    public data class Rejected(val reason: AdmissionFailure) : CommandSubmission
    public data class Accepted(val receipt: Deferred<CommandOutcome>) : CommandSubmission
}

public enum class AdmissionFailure { Disconnected, Capacity, Closed }

/** Acknowledgement covers RPC replies only, not observation of the resulting terminal state. */
public sealed interface CommandOutcome {
    public data object Acknowledged : CommandOutcome
    public data class Failed(val failure: HostFailure, val uncertain: Boolean) : CommandOutcome
    public data object Cancelled : CommandOutcome
    /** At least part of this resize was replaced before dispatch. No acknowledgement is implied. */
    public data object Superseded : CommandOutcome
}
