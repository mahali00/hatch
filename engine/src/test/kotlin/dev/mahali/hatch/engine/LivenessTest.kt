package dev.mahali.hatch.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LivenessTest {
    @Test fun `a healthy link is left alone and a dead one reconnects once even when nudged repeatedly`() = runTest(UnconfinedTestDispatcher()) {
        val liveness = Liveness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        var probes = 0
        var dead = 0
        val answer = CompletableDeferred<Boolean>()
        repeat(3) { liveness.check("h", { probes++; answer.await() }, { dead++ }) }
        assertEquals(1, probes)
        answer.complete(true)
        assertEquals(0, dead)
        liveness.check("h", { probes++; false }, { dead++ })
        assertEquals(2, probes)
        assertEquals(1, dead)
    }
}
