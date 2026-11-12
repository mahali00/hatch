package dev.mahali.hatch.feature.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewCopyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun expiredCopiesAreRemovedRecursivelyButAnActiveCopyIsNot() {
        val root = temporary.newFolder()
        val old = PreviewCopy.create(root, "shared.pdf")
        old.file.writeText("shared bytes")
        old.retain(1_000)
        val active = PreviewCopy.create(root, "downloading.pdf")
        active.file.writeText("partial bytes")
        active.file.parentFile!!.setLastModified(1_000)
        try {
            PreviewCopy.clean(root, 3_602_000)
            assertFalse(old.file.parentFile!!.exists())
            assertTrue(active.file.exists())
        } finally {
            active.close()
        }
        assertFalse(active.file.parentFile!!.exists())
    }

    @Test fun handingOverACopyKeepsItAfterViewerCloseUntilRetentionExpires() {
        val root = temporary.newFolder()
        val copy = PreviewCopy.create(root, "shared.txt")
        copy.file.writeText("recipient still needs these bytes")
        copy.retain(2_000)
        copy.close()
        PreviewCopy.clean(root, 3_601_000)
        assertTrue(copy.file.exists())
        PreviewCopy.clean(root, 3_603_000)
        assertFalse(copy.file.parentFile!!.exists())
    }
}
