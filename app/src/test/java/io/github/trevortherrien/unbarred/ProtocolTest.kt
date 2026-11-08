package io.github.trevortherrien.unbarred

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM unit tests for the protocol tables and control-write framing. */
class ProtocolTest {

    // ---- value maps ----
    @Test fun eqValues() {
        assertEquals(0, Proto.EQ["Movie"])
        assertEquals(1, Proto.EQ["Music"])
        assertEquals(3, Proto.EQ["Direct"])
    }

    @Test fun surroundValuesInOrder() {
        assertEquals(listOf(0, 1, 2, 3), Proto.SURROUND.values.toList())
    }

    @Test fun ledHasEightColors() {
        assertEquals(8, Proto.LED.size)
        assertEquals(6, Proto.LED["Cyan"])
    }

    // ---- UUID base + suffix ----
    @Test fun uuidsUseCorrectBaseAndSuffix() {
        assertTrue(Proto.SERVICE_INFO.toString().endsWith("37619c1da100"))
        assertTrue(Proto.CHAR_VZ_EQ.toString().endsWith("37619c1db10d"))
        assertTrue(Proto.CHAR_DIALOG.toString().endsWith("37619c1dc106"))
    }

    // ---- framing (the exact bytes sent to the bar) ----
    @Test fun frame_unsecured_isRawByte() {
        assertArrayEquals(byteArrayOf(1), Framing.frame(null, false, 1))
    }

    @Test fun frame_signedValue_isTwosComplement() {
        // -6 must go on the wire as 0xFA
        assertArrayEquals(byteArrayOf(0xFA.toByte()), Framing.frame(null, false, -6))
    }

    @Test fun frame_secured_prependsTokenAscii() {
        val expected = "TOK:".toByteArray(Charsets.US_ASCII) + byteArrayOf(6)
        assertArrayEquals(expected, Framing.frame("TOK", true, 6))
    }

    @Test fun frame_securedButNoTokenYet_fallsBackToRaw() {
        assertArrayEquals(byteArrayOf(6), Framing.frame(null, true, 6))
    }
}
