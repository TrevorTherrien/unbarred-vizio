package io.github.trevortherrien.unbarred

import java.util.UUID

/**
 * VIZIO soundbar BLE protocol, as documented in PROTOCOL.md.
 * All custom UUIDs share base  ea1979cf-5313-4152-b056-37619c1dXXXX  (A/B/C/D/F groups)
 * and  ...37619c1eXXXX  (OTA / karaoke groups).
 */
object Proto {
    private fun u(suffix: String) = UUID.fromString("ea1979cf-5313-4152-b056-37619c1d$suffix")
    private fun u1e(suffix: String) = UUID.fromString("ea1979cf-5313-4152-b056-37619c1e$suffix")

    // Service A — info
    val SERVICE_INFO: UUID = u("a100")
    val CHAR_MODEL_NAME: UUID = u("a101")
    val CHAR_SERIAL: UUID = u("a102")
    val CHAR_FW_VERSION: UUID = u("a103")
    val CHAR_CURRENT_INPUT: UUID = u("a104")
    val CHAR_AUDIO_OUT: UUID = u("a105")
    val CHAR_FRIENDLY_NAME: UUID = u("a106")
    val CHAR_SECURITY_GEN: UUID = u("a107")

    // Service B — main menu
    val SERVICE_MAIN_MENU: UUID = u("b100")
    val CHAR_SB_INFO: UUID = u("b101")          // read: soundbar info/state blob
    val CHAR_AUTO_VA: UUID = u("b102")
    val CHAR_ECO_POWER: UUID = u("b103")
    val CHAR_GET_INPUT: UUID = u("b104")        // read trigger for current input
    val CHAR_DISCOVERED_INPUTS: UUID = u("b105") // read: list of available inputs
    val CHAR_VOLUME: UUID = u("b106")
    val CHAR_MUTE: UUID = u("b107")
    val CHAR_POWER_LED: UUID = u("b108")
    val CHAR_ANNOUNCEMENT: UUID = u("b109")
    val CHAR_AV_DELAY: UUID = u("b10a")
    val CHAR_FACTORY_RESET: UUID = u("b10b")
    val CHAR_SPEAKER_TEST: UUID = u("b10c")
    val CHAR_VZ_EQ: UUID = u("b10d")
    val CHAR_SEL_INPUT: UUID = u("b10e")
    val CHAR_ELEVATE_LED: UUID = u("b10f")
    val CHAR_CLASSIC_PAIRING: UUID = u("b110")  // write: enter BT (A2DP) pairing mode
    val CHAR_AUTO_HDMI: UUID = u("b111")
    val CHAR_CLASSIC_STATUS: UUID = u("b112")   // read: BT (A2DP) connection status

    // Service C — levels
    val SERVICE_LEVELS: UUID = u("c100")
    val CHAR_BASS: UUID = u("c101")
    val CHAR_TREBLE: UUID = u("c102")
    val CHAR_CENTER: UUID = u("c103")
    val CHAR_SUBWOOFER: UUID = u("c104")
    val CHAR_SURR_LEVEL: UUID = u("c105")
    val CHAR_DIALOG: UUID = u("c106")
    val CHAR_SURR_BALANCE: UUID = u("c107")
    val CHAR_HEIGHT: UUID = u("c108")
    val CHAR_WLESS_SPK_ST: UUID = u("c109")
    val CHAR_WIDE_SURR_LVL: UUID = u("c10a")
    val CHAR_WIDE_SURR_BAL: UUID = u("c10b")

    // Service D — effects
    val SERVICE_EFFECTS: UUID = u("d100")
    val CHAR_SURR_SPEAKERS: UUID = u("d101")
    val CHAR_TRU_VOLUME: UUID = u("d102")
    val CHAR_VIRTUAL_X: UUID = u("d103")
    val CHAR_NIGHT_MODE: UUID = u("d104")
    val CHAR_ELEVATE_SPK: UUID = u("d105")

    // Service F — security + device response/error/status channels
    val SERVICE_SECURITY: UUID = u("f100")
    val CHAR_RESPONSE: UUID = u("f101")         // read: last command response
    val CHAR_ERROR: UUID = u("f102")            // read: device error channel
    val CHAR_STATUS: UUID = u("f103")           // read: device status channel
    val CHAR_SEC_HANDSHAKE: UUID = u("f104")
    val CHAR_SEC_PRESHARED: UUID = u("f105")

    // CCCD (not needed for the handshake, kept for completeness)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- value maps (see PROTOCOL.md §4) ----
    val EQ = linkedMapOf("Movie" to 0, "Music" to 1, "Direct" to 3)
    val LED = linkedMapOf(
        "Off" to 0, "Rainbow" to 1, "Light Blue" to 2, "Yellow" to 3,
        "Purple" to 4, "White" to 5, "Cyan" to 6, "Magenta" to 7
    )
    val SURROUND = linkedMapOf("Off" to 0, "On" to 1, "Dual" to 2, "Front" to 3)
    val ELEVATE = linkedMapOf("Up" to 0, "Wide" to 1, "Auto" to 2)
    val INPUT = linkedMapOf("USB" to 0, "Bluetooth" to 1, "HDMI" to 2)
}

/**
 * Fixed values for the local handshake (see PROTOCOL.md §5).
 *
 * The bar expects a 14-byte response that depends only on the client's device ID, never on the
 * bar's challenge — so for one fixed ID it is a constant. We replay that constant rather than
 * deriving it. Changing [DEVICE_ID] requires the matching [RESPONSE].
 */
object Handshake {
    /** Client device ID sent in step 1 (exactly 14 ASCII chars). */
    const val DEVICE_ID = "cafe0babe0dead"

    /** Response bytes sent after "<challenge>:" in step 3; valid for [DEVICE_ID] only. */
    val RESPONSE: ByteArray = intArrayOf(
        0xd0, 0x90, 0x28, 0xc0, 0x88, 0x28, 0x00,
        0xa8, 0xb0, 0x28, 0x18, 0x88, 0xb0, 0xa8,
    ).let { v -> ByteArray(v.size) { v[it].toByte() } }
}

/**
 * The exact bytes written to a control characteristic — a pure function so it can be
 * unit-tested without a device. If the security service is active, the pairing token
 * is prepended as ASCII `"<token>:"`; otherwise the raw signed value byte is sent.
 */
object Framing {
    fun frame(pairingToken: String?, secured: Boolean, value: Int): ByteArray {
        val v = byteArrayOf((value and 0xFF).toByte())
        return if (secured && pairingToken != null)
            ("$pairingToken:").toByteArray(Charsets.US_ASCII) + v
        else v
    }
}
