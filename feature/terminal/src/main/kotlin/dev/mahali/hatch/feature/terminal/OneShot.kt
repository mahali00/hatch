package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.engine.Mods

internal enum class ModKey { Super, Ctrl, Alt, Shift }

internal data class OneShot(val armed: Set<ModKey> = emptySet()) {
    fun toggle(key: ModKey): OneShot = OneShot(if (key in armed) armed - key else armed + key)

    val mods: Mods
        get() = Mods(shift = ModKey.Shift in armed, ctrl = ModKey.Ctrl in armed, alt = ModKey.Alt in armed, meta = ModKey.Super in armed)

    companion object {
        val None: OneShot = OneShot()
    }
}
