package dev.mahali.hatch.engine

import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.protocol.StateCodec
import java.io.File

/** Each host's last state on disk, in [StateCodec]'s format. */
public class StateCache(private val dir: File) {
    private fun file(host: Host) = File(dir, host.id.value.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    public fun load(host: Host): HostState? = runCatching { file(host).takeIf { it.isFile }?.readText() }.getOrNull()
        ?.let { StateCodec.decode(host, it) }

    public fun save(state: HostState) {
        runCatching {
            dir.mkdirs()
            val target = file(state.host)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(StateCodec.encode(state))
            temp.renameTo(target)
        }
    }
}
