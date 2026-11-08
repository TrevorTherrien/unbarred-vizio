package io.github.trevortherrien.unbarred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the high-level setters route to the correct characteristics (JVM, no device). */
class FakeSoundbarControllerTest {

    @Test fun scanAndConnect_setsConnected() {
        val c = FakeSoundbarController()
        assertFalse(c.connected.value)
        c.scanAndConnect()
        assertTrue(c.connected.value)
    }

    @Test fun disconnect_clearsConnected() {
        val c = FakeSoundbarController().apply { scanAndConnect() }
        c.disconnect()
        assertFalse(c.connected.value)
    }

    @Test fun setEq_routesToEqCharacteristic() {
        val c = FakeSoundbarController()
        c.setEq(1)
        val w = c.writes.single()
        assertEquals(Proto.SERVICE_MAIN_MENU, w.service)
        assertEquals(Proto.CHAR_VZ_EQ, w.char)
        assertEquals(1, w.value)
    }

    @Test fun setDialog_routesToClearDialogCharacteristic() {
        val c = FakeSoundbarController()
        c.setDialog(3)
        val w = c.writes.single()
        assertEquals(Proto.SERVICE_LEVELS, w.service)
        assertEquals(Proto.CHAR_DIALOG, w.char)
        assertEquals(3, w.value)
    }

    @Test fun scanAndConnect_reachesReadyPhase() {
        val c = FakeSoundbarController()
        assertEquals(LinkPhase.IDLE, c.phase.value)
        c.scanAndConnect()
        assertEquals(LinkPhase.READY, c.phase.value)
        c.disconnect()
        assertEquals(LinkPhase.IDLE, c.phase.value)
    }

    @Test fun nudgeVolume_stepsFromLastValueAndClamps() {
        val c = FakeSoundbarController().apply { scanAndConnect() }   // fake volume = 20
        c.nudgeVolume(2); c.nudgeVolume(2)                           // rapid presses compound
        assertEquals(24, c.controlValues.value[Proto.CHAR_VOLUME])
        c.nudgeVolume(-500)
        assertEquals(0, c.controlValues.value[Proto.CHAR_VOLUME])
        c.nudgeVolume(-1)                                            // already at floor: no write
        assertEquals(3, c.writes.size)
    }

    @Test fun nudgeVolume_noopBeforeVolumeKnown() {
        val c = FakeSoundbarController()
        c.nudgeVolume(2)
        assertTrue(c.writes.isEmpty())
    }

    @Test fun cycleEq_wrapsMovieMusicDirect() {
        val c = FakeSoundbarController().apply { scanAndConnect() }   // fake EQ = Movie (0)
        c.cycleEq(); assertEquals(1, c.writes.last().value)
        c.cycleEq(); assertEquals(3, c.writes.last().value)
        c.cycleEq(); assertEquals(0, c.writes.last().value)
    }

    @Test fun profileLimits_matchSl512x() {
        val p = ModelProfiles.forModel("SL512X-0806")
        assertEquals(0..10, p.dialogRange)
        assertEquals(8, p.avDelayMax)
    }

    @Test fun booleanSetters_sendOneOrZero() {
        val c = FakeSoundbarController()
        c.setNight(true)
        c.setMute(false)
        assertEquals(1, c.writes[0].value)
        assertEquals(0, c.writes[1].value)
    }
}
