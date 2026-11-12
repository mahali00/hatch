package dev.mahali.hatch.protocol

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class HelperOutputTest {
    /** Terminal output as the helper prints it: \r\n line ends, base64 wrapped at 76 like GNU base64. */
    private fun printed(offset: Long, bytes: ByteArray): ByteArray {
        val b64 = Base64.getMimeEncoder().encodeToString(bytes)
        return "HATCH-BEGIN $offset ${bytes.size}\r\n$b64\r\n\r\nHATCH-END $offset\r\n".encodeToByteArray()
    }

    /** Feeds [bytes] in uneven pieces, so markers arrive split across appends. */
    private fun HelperOutput.feed(bytes: ByteArray) {
        var i = 0
        var n = 1
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + n)
            append(bytes.copyOfRange(i, end))
            i = end
            n = n * 3 % 997 + 1
        }
    }

    @Test fun readsSizeThenRangesSplitAcrossAppends() = runTest {
        val out = HelperOutput()
        val file = Random(7).nextBytes(300_000)
        val size = async { out.meta() }
        out.feed("go\r\nHATCH-META 300000\r\n".encodeToByteArray())
        assertEquals(300_000, size.await())

        for ((off, len) in listOf(0L to 4096, 4096L to 65536, 250_000L to 50_000)) {
            val range = async { out.range(off, len) }
            out.feed(printed(off, file.copyOfRange(off.toInt(), off.toInt() + len)))
            assertContentEquals(file.copyOfRange(off.toInt(), off.toInt() + len), range.await())
        }
    }

    @Test fun macOsBase64OnOneLineDecodes() = runTest {
        val out = HelperOutput()
        val bytes = Random(1).nextBytes(10_000)
        out.append("HATCH-BEGIN 0 10000\n${Base64.getEncoder().encodeToString(bytes)}\nHATCH-END 0\n".encodeToByteArray())
        assertContentEquals(bytes, out.range(0, 10_000))
    }

    @Test fun notAFileFails() = runTest {
        val out = HelperOutput()
        out.append("HATCH-ERR notfile\r\n".encodeToByteArray())
        assertEquals("not_found", assertFailsWith<RexError> { out.meta() }.code)
    }

    @Test fun countsEverythingReceived() {
        val out = HelperOutput()
        out.append(ByteArray(10)); out.append(ByteArray(5))
        assertEquals(15, out.received)
    }
}
