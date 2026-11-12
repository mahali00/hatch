package dev.mahali.hatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InBandTest {
    private val nonce = 42L
    private fun decode(json: String, n: String = "42") = decodeInBand("rex1;$n;$json", nonce)

    @Test fun `a resize needs cols and rows, and cell sizes default to 0`() {
        assertEquals(InBandMessage.Resized(80, 24, 9, 18), decode("""{"type":"resize","cols":80,"rows":24,"cell_width_px":9,"cell_height_px":18}"""))
        assertEquals(InBandMessage.Resized(80, 24, 0, 0), decode("""{"type":"resize","cols":80,"rows":24}"""))
        assertNull(decode("""{"type":"resize","cols":80}"""))
    }

    @Test fun `theme colours parse with or without a hash, -1 when invalid`() {
        val t = decode("""{"type":"theme","foreground":"#ffffff","background":"1e1e2e","cursor":"nope","palette":["1=ff0000","256=00ff00","x=0000ff","2=zzzzzz","3",4,"15=#00ff00"]}""")
        assertEquals(InBandMessage.Themed(0xffffff, 0x1e1e2e, -1, listOf(1 to 0xff0000, 15 to 0x00ff00)), t)
        assertEquals(InBandMessage.Themed(-1, -1, -1, emptyList()), decode("""{"type":"theme"}"""))
    }

    @Test fun `an exit's negative code is unknown`() {
        assertEquals(InBandMessage.Exited(3, "boom"), decode("""{"type":"child_exited","exit_code":3,"error":"boom"}"""))
        assertEquals(InBandMessage.Exited(null, ""), decode("""{"type":"child_exited","exit_code":-1}"""))
    }

    @Test fun `the nonce is unsigned and must match`() {
        val big = ULong.MAX_VALUE
        assertEquals(InBandMessage.Exited(0, ""), decodeInBand("""rex1;$big;{"type":"child_exited","exit_code":0}""", big.toLong()))
        assertNull(decode("""{"type":"child_exited"}""", n = "43"))
        assertNull(decode("""{"type":"child_exited"}""", n = "-42"))
    }

    @Test fun `malformed or unknown messages are null`() {
        assertNull(decodeInBand("rex1;42", nonce))
        assertNull(decode("not json"))
        assertNull(decode("[1]"))
        assertNull(decode("""{"type":"future"}"""))
        assertEquals(InBandMessage.Exited(null, "a;b"), decode("""{"type":"child_exited","error":"a;b"}"""))
    }
}
