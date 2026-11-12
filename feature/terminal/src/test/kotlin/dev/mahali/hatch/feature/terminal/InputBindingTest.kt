package dev.mahali.hatch.feature.terminal

import org.junit.Assert.*
import org.junit.Test

class InputBindingTest {
    @Test fun staleCallbacksCannotSubmitAfterFocusMovesOrReturns() {
        val inputs = InputBindings<String>()
        val a = inputs.bind("a", true)
        val accepted = mutableListOf<Pair<String, String>>()
        fun submit(binding: InputBinding<String>, text: String) {
            if (binding.acceptsInput) accepted += binding.destination to text
        }
        submit(a, "already accepted")
        val b = inputs.bind("b", true)
        a.close()
        submit(a, "stale a")
        submit(b, "b input")
        val nextA = inputs.bind("a", true)
        submit(a, "stale after return")
        submit(b, "stale b")
        submit(nextA, "new a")
        assertEquals(listOf("a" to "already accepted", "b" to "b input", "a" to "new a"), accepted)
    }

    @Test fun readinessBelongsToTheDestinationGenerationAndCloseIsIdempotent() {
        val changes = mutableListOf<String?>()
        val inputs = InputBindings<String> { changes += it?.destination }
        val ready = inputs.bind("a", true)
        val blocked = inputs.bind("b", false)
        assertFalse(ready.acceptsInput)
        assertFalse(blocked.acceptsInput)
        val restored = inputs.bind("b", true)
        blocked.close()
        assertTrue(restored.acceptsInput)
        restored.close()
        restored.close()
        assertFalse(restored.acceptsInput)
        assertEquals(listOf("a", null, "b", null, "b", null), changes)
    }
}
