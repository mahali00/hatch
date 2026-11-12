package dev.mahali.hatch.feature.terminal

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class KeyboardRowsTest {
    @Test fun `a shell keeps its prompt line`() = assertEquals(11, keyboardRows(fullScreen = false, cursorRow = 10, rows = 50))
    @Test fun `a full-screen input keeps the rows under it, not the screen's bottom`() = assertEquals(34, keyboardRows(true, 29, 50))
    @Test fun `near the bottom it stops at the last row`() = assertEquals(50, keyboardRows(true, 48, 50))
    @Test fun `a full-screen program with no cursor keeps its bottom edge`() = assertEquals(50, keyboardRows(true, null, 50))
    @Test fun `no cursor in a shell stays put`() = assertNull(keyboardRows(false, null, 50))
}
