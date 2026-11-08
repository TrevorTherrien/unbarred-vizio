package io.github.trevortherrien.unbarred

/**
 * A snapshot of values the bar actually reports over BLE, read on connect (and on demand).
 *
 * The vendor app does NOT subscribe to notifications — it polls reads — so this is likewise a
 * point-in-time read, not a live subscription. Text fields (serial/firmware/friendly name) are
 * ASCII; single-byte enums (current input, audio-out, classic status) are stored as Int. Blobs
 * whose wire format we haven't confirmed yet (discovered-inputs, wireless-speaker-status,
 * and the F101–F103 response/error/status channels) are kept as hex so nothing is misreported —
 * the UI can render what's known and the hex is there for diagnostics.
 *
 * Everything is a nullable String/Int so the data class has value equality and [kotlinx] StateFlow
 * de-dupes correctly (a raw ByteArray would break both).
 */
data class DeviceState(
    val modelName: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val friendlyName: String? = null,
    /** CHAR_CURRENT_INPUT (A104) returns a text token (e.g. "ARC"), NOT the write-enum index. */
    val currentInputText: String? = null,
    val currentInputHex: String? = null,
    val audioOut: Int? = null,
    /** Hex of CHAR_DISCOVERED_INPUTS (B105) — wire format not yet confirmed. */
    val discoveredInputsHex: String? = null,
    /** Hex of CHAR_WIRELESS_SPEAKER_STATUS (C109) — surround/sub pairing state. */
    val speakerStatusHex: String? = null,
    /** Raw byte from CHAR_CLASSIC_STATUS (B112) — BT (A2DP) connection state. */
    val classicStatus: Int? = null,

    // Diagnostic channels (read-only, for the status-14 investigation). Raw hex.
    val lastResponseHex: String? = null,
    val lastErrorHex: String? = null,
    val lastStatusHex: String? = null,
) {
    /** True once any field has been populated from a real read. */
    val isPopulated: Boolean
        get() = modelName != null || serial != null || firmware != null ||
            friendlyName != null || currentInputText != null || speakerStatusHex != null
}
