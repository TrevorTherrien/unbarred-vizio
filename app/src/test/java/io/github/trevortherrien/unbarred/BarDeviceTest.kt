package io.github.trevortherrien.unbarred

import org.junit.Assert.assertEquals
import org.junit.Test

/** Name precedence, later wins: broadcast name → phone's Bluetooth nickname → Unbarred nickname. */
class BarDeviceTest {

    private fun bar(advertised: String? = "VIZIO SB", os: String? = null, app: String? = null) =
        BarDevice("AA:BB:CC:DD:EE:FF", advertised, -60, nickname = app, osAlias = os)

    @Test fun broadcastNameByDefault() = assertEquals("VIZIO SB", bar().label)

    @Test fun osNicknameOverridesBroadcast() = assertEquals("Den", bar(os = "Den").label)

    @Test fun appNicknameOverridesBoth() = assertEquals("Living Room", bar(os = "Den", app = "Living Room").label)

    @Test fun blankNicknamesAreIgnored() = assertEquals("VIZIO SB", bar(os = " ", app = "").label)

    @Test fun genericFallbackWhenNothingKnown() = assertEquals("VIZIO bar", bar(advertised = null).label)
}
