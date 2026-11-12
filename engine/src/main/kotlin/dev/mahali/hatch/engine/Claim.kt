package dev.mahali.hatch.engine

internal enum class Claim {
    None,
    Sent,
    Held,
    Lost,
}

internal fun Claim.observe(owner: String?, me: String?): Claim = when {
    me == null -> this
    (this == Claim.Sent || this == Claim.Lost) && owner == me -> Claim.Held
    // Server dropped the hold (e.g. on reconnect): claim again.
    this == Claim.Held && owner == null -> Claim.None
    this == Claim.Held && owner != me -> Claim.Lost
    else -> this
}

internal fun Claim.onFocus(): Claim = if (this == Claim.Lost) Claim.None else this
