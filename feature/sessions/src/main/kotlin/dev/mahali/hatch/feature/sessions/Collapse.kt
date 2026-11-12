package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.SessionId

internal fun Set<String>.toggled(id: SessionId): Set<String> = if (id.value in this) this - id.value else this + id.value

internal fun Set<String>.collapsingAllBut(keep: SessionId, state: AppState): Set<String> =
    this + state.byHost.values.flatMap { it.sessions }.map { it.value } - keep.value
