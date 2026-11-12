package dev.mahali.hatch.feature.terminal

import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageSizeTest {
    @Test fun extremePageShapesStayWithinMemoryAndTextureBudgets() {
        for ((width, height) in listOf(1 to 14400, 14400 to 1, 14400 to 14400, 595 to 842)) {
            for (budget in listOf(1_000_000, 4_000_000)) {
                val size = pdfPageSize(width, height, 2500, budget)
                assertTrue("$width x $height produced $size", size.width in 1..2500 && size.height in 1..4096)
                assertTrue(size.width.toLong() * size.height <= budget)
            }
        }
    }
}
