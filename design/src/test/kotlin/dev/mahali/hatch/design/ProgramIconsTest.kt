package dev.mahali.hatch.design

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramIconsTest {
    // The catalogue is private to :core; reading it here keeps the two lists from drifting apart.
    @Suppress("UNCHECKED_CAST")
    private val known = Class.forName("dev.mahali.hatch.core.ProgramKeyKt").getDeclaredField("known")
        .apply { isAccessible = true }.get(null) as Set<String>

    @Test fun everyKnownProgramHasAnIcon() {
        assertEquals(emptySet<String>(), known + "terminal" - programIcons.keys)
    }

    @Test fun noIconForAnUnknownProgram() {
        assertEquals(emptySet<String>(), programIcons.keys - known)
    }
}
