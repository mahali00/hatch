package dev.mahali.hatch.engine

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A link believed Online can be dead after a silent NAT drop; ping would only say so 30-60 s later. */
internal class Liveness(private val scope: CoroutineScope) {
    private val running = ConcurrentHashMap.newKeySet<Any>()

    fun check(key: Any, alive: suspend () -> Boolean, dead: () -> Unit) {
        if (!running.add(key)) return
        scope.launch {
            try {
                if (!alive()) dead()
            } catch (e: CancellationException) {
                throw e
            } finally {
                running.remove(key)
            }
        }
    }
}
