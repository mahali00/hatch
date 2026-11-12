package dev.mahali.hatch.feature.terminal

import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.engine.Mods
import dev.mahali.hatch.engine.Pane
import dev.mahali.hatch.engine.PaneAcquisition
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.engine.KeyTranslator
import dev.mahali.hatch.engine.namedKey
import dev.mahali.hatch.protocol.DataStream

internal data class PaneRef(val host: HostId, val session: SessionId, val block: BlockId)

internal class Target(val ref: PaneRef, val acquisition: PaneAcquisition) {
    val pane: Pane get() = acquisition.pane
}

// One-shot modifiers from the key bar apply to the next key from either source.
@Stable
internal class Typing(private val rex: Rex, private val haptics: Haptics) {
    var armed: OneShot by mutableStateOf(OneShot.None)
        private set

    private var binding: InputBinding<Target>? by mutableStateOf(null)
    private val bindings = InputBindings<Target> { binding = it }
    val enabled: Boolean get() = binding?.let(::accepts) == true

    // Also check the pane's live stream state, not only the last composed readiness.
    fun accepts(input: InputBinding<Target>): Boolean = input.acceptsInput && input.destination.acquisition.isActive && input.destination.pane.status.value.let {
        it.restored && it.stream == DataStream.State.Live && !it.exited
    }

    // Destination and readiness change together, which invalidates the previous generation's callbacks.
    fun bind(target: Target, ready: Boolean): InputBinding<Target> =
        bindings.bind(target, ready)

    fun unbind(target: Target) {
        binding?.takeIf { it.destination === target }?.close()
    }

    fun toggle(key: ModKey) {
        haptics.key()
        armed = armed.toggle(key)
    }

    fun text(to: InputBinding<Target>, text: String) {
        if (!accepts(to) || text.isEmpty()) return
        val mods = consume(to.destination)
        val ref = to.destination.ref
        rex.text(ref.host, ref.session, ref.block, text, mods)
    }

    fun shortcut(text: String) {
        haptics.key()
        binding?.let { text(it, text) }
    }

    private val keys = KeyTranslator()

    // False for non-terminal keys (volume, media), so Android handles them.
    fun key(to: InputBinding<Target>, event: KeyEvent): Boolean {
        if (!accepts(to)) return true
        val sent = keys.translate(event, armed.mods) ?: return false
        if (sent.isEmpty()) return true
        val target = to.destination
        consume(target)
        for (key in sent) rex.key(target.ref.host, target.ref.session, target.ref.block, key)
        return true
    }

    fun backspace(to: InputBinding<Target>, count: Int) {
        if (!accepts(to)) return
        repeat(count) { named(to, "backspace") }
    }

    fun named(name: String) {
        haptics.key()
        binding?.let { named(it, name) }
    }

    private fun named(to: InputBinding<Target>, name: String) {
        if (!accepts(to)) return
        val target = to.destination
        rex.key(target.ref.host, target.ref.session, target.ref.block, namedKey(name, consume(target)))
    }

    private fun consume(to: Target): Mods {
        if (to.pane.geometry.scroll.scrolledBack) to.pane.scrollToBottom()
        val mods = armed.mods
        if (armed != OneShot.None) armed = OneShot.None
        return mods
    }
}
