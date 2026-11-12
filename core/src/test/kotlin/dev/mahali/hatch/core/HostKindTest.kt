package dev.mahali.hatch.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HostKindTest {
    @Test fun `saved laptop and desktop choices survive the expanded catalogue`() {
        assertEquals(HostKind.MacBook, HostKind.fromStored("Laptop"))
        assertEquals(HostKind.Desktop, HostKind.fromStored("Desktop"))
    }

    @Test fun `missing or future device types use the default icon`() {
        assertEquals(HostKind.MacBook, HostKind.fromStored(null))
        assertEquals(HostKind.MacBook, HostKind.fromStored("future-device"))
    }
}
