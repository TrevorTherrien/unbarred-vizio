package io.github.trevortherrien.unbarred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the fixed handshake values so an accidental edit can't silently break authentication. */
class HandshakeTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun deviceId_isExactly14AsciiChars() {
        assertEquals(14, Handshake.DEVICE_ID.length)
        assertTrue(Handshake.DEVICE_ID.all { it.code in 0x20..0x7e })
    }

    @Test
    fun response_matchesDeviceId() {
        // Response for DEVICE_ID "cafe0babe0dead"; confirmed working against an SL512X.
        assertEquals("d09028c0882800a8b0281888b0a8", hex(Handshake.RESPONSE))
        assertEquals(14, Handshake.RESPONSE.size)
    }
}
