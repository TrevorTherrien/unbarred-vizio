package io.github.trevortherrien.unbarred

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Abstraction over the soundbar. The UI, ViewModel and tests depend only on this —
 * not on Android Bluetooth — so the app can run against a real BLE device or an
 * in-memory [FakeSoundbarController] (emulator, @Preview, UI tests).
 */
/** Where the link is in its lifecycle. Controls should only be interactive in [READY]. */
enum class LinkPhase(val label: String, val busy: Boolean) {
    IDLE("Not connected", false),
    SCANNING("Scanning for sound bars…", true),
    CONNECTING("Connecting…", true),
    AUTHENTICATING("Authenticating…", true),
    SYNCING("Reading current settings…", true),
    READY("Connected", false),
}

interface SoundbarController {
    val status: StateFlow<String>
    /** Fine-grained link lifecycle, for progress UI. */
    val phase: StateFlow<LinkPhase>
    /** True once the link is authenticated (may still be [LinkPhase.SYNCING]). */
    val connected: StateFlow<Boolean>
    val deviceName: StateFlow<String?>

    /** Transient one-shot messages (failures, unexpected disconnects) for toasts. */
    val events: SharedFlow<String>

    /** What the connected model supports (ranges, feature presence). */
    val capabilities: StateFlow<Capabilities>

    /** Values the bar actually reports (read on connect and on demand). */
    val deviceState: StateFlow<DeviceState>

    /**
     * Live per-characteristic control values, keyed by characteristic UUID. Seeded by reading
     * every control on connect, then kept current by NOTIFY (so external changes — the IR remote,
     * another phone — show up here too). Level values are signed (−N..N); toggles are 0/1; choice
     * controls are their enum index.
     */
    val controlValues: StateFlow<Map<UUID, Int>>

    /** Bars seen in the current scan (empty when not scanning / after connecting). */
    val scanResults: StateFlow<List<BarDevice>>
    /** True while a scan is in progress. */
    val scanning: StateFlow<Boolean>

    /** Caller must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT (Android 12+) first.
     *  Scans briefly: exactly one bar → connects automatically; several → populates
     *  [scanResults] for the UI to present a picker (via [connect]). */
    fun scanAndConnect()
    /** Connect to a specific scanned bar by MAC address. */
    fun connect(address: String)
    fun disconnect()

    /** Assign or clear a persisted nickname for a bar (null/blank clears it). */
    fun rename(address: String, nickname: String?)

    /** Re-read the bar's reported state into [deviceState]. No-op if not connected. */
    fun refreshState()

    /** Write a single-byte control value to (service, char). [label] feeds the status text. */
    fun write(service: UUID, char: UUID, value: Int, label: String)
}

// ---- high-level convenience setters, shared by every implementation ----
fun SoundbarController.setEq(v: Int)        = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_VZ_EQ, v, "EQ")
fun SoundbarController.setLed(v: Int)       = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_ELEVATE_LED, v, "LED")
fun SoundbarController.setInput(v: Int)     = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_SEL_INPUT, v, "Input")
fun SoundbarController.setSurround(v: Int)  = write(Proto.SERVICE_EFFECTS, Proto.CHAR_SURR_SPEAKERS, v, "Surround")
fun SoundbarController.setElevate(v: Int)   = write(Proto.SERVICE_EFFECTS, Proto.CHAR_ELEVATE_SPK, v, "Height aim")

fun SoundbarController.setVolume(v: Int)    = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_VOLUME, v, "Volume")
fun SoundbarController.setBass(v: Int)      = write(Proto.SERVICE_LEVELS, Proto.CHAR_BASS, v, "Bass")
fun SoundbarController.setTreble(v: Int)    = write(Proto.SERVICE_LEVELS, Proto.CHAR_TREBLE, v, "Treble")
fun SoundbarController.setCenter(v: Int)    = write(Proto.SERVICE_LEVELS, Proto.CHAR_CENTER, v, "Center")
fun SoundbarController.setDialog(v: Int)    = write(Proto.SERVICE_LEVELS, Proto.CHAR_DIALOG, v, "Dialog")
fun SoundbarController.setSub(v: Int)       = write(Proto.SERVICE_LEVELS, Proto.CHAR_SUBWOOFER, v, "Sub")
fun SoundbarController.setHeight(v: Int)    = write(Proto.SERVICE_LEVELS, Proto.CHAR_HEIGHT, v, "Height")
fun SoundbarController.setSurrLevel(v: Int) = write(Proto.SERVICE_LEVELS, Proto.CHAR_SURR_LEVEL, v, "Surround level")
fun SoundbarController.setSurrBalance(v: Int) = write(Proto.SERVICE_LEVELS, Proto.CHAR_SURR_BALANCE, v, "Surround balance")
fun SoundbarController.setWideSurrLevel(v: Int) = write(Proto.SERVICE_LEVELS, Proto.CHAR_WIDE_SURR_LVL, v, "Wide surround level")
fun SoundbarController.setWideSurrBalance(v: Int) = write(Proto.SERVICE_LEVELS, Proto.CHAR_WIDE_SURR_BAL, v, "Wide surround balance")

fun SoundbarController.setNight(on: Boolean)    = write(Proto.SERVICE_EFFECTS, Proto.CHAR_NIGHT_MODE, on.b(), "Night")
fun SoundbarController.setVirtualX(on: Boolean) = write(Proto.SERVICE_EFFECTS, Proto.CHAR_VIRTUAL_X, on.b(), "Virtual:X")
fun SoundbarController.setTruVolume(on: Boolean)= write(Proto.SERVICE_EFFECTS, Proto.CHAR_TRU_VOLUME, on.b(), "TruVolume")
fun SoundbarController.setMute(on: Boolean)     = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_MUTE, on.b(), "Mute")

fun SoundbarController.setAvDelay(v: Int)   = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_AV_DELAY, v, "AV delay")
fun SoundbarController.setPowerLed(v: Int)  = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_POWER_LED, v, "Power LED")
fun SoundbarController.setEcoPower(v: Int)  = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_ECO_POWER, v, "Eco power")
fun SoundbarController.setAutoHdmi(on: Boolean) = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_AUTO_HDMI, on.b(), "Auto-HDMI")
fun SoundbarController.setAutoVa(on: Boolean)   = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_AUTO_VA, on.b(), "Auto voice-assistant")
fun SoundbarController.setAnnounce(on: Boolean) = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_ANNOUNCEMENT, on.b(), "Announcements")
/** Not used by the UI: on the SL512X this leaves the bar unresponsive until power-cycled. */
@Deprecated("Leaves the bar unresponsive until power-cycled (SL512X); see PROTOCOL.md §7")
fun SoundbarController.speakerTest(on: Boolean) = write(Proto.SERVICE_MAIN_MENU, Proto.CHAR_SPEAKER_TEST, on.b(), "Speaker test")

private fun Boolean.b() = if (this) 1 else 0

// ---- relative controls (volume keys, notification buttons) ----
const val VOLUME_MAX = 100

/** Step the bar's volume by [delta] from its last known value. No-op until volume has been read. */
fun SoundbarController.nudgeVolume(delta: Int) {
    val current = controlValues.value[Proto.CHAR_VOLUME] ?: return
    val next = (current + delta).coerceIn(0, VOLUME_MAX)
    if (next != current) setVolume(next)
}

fun SoundbarController.toggleMute() {
    val muted = controlValues.value[Proto.CHAR_MUTE] == 1
    setMute(!muted)
}

/** Advance to the next EQ preset (Movie → Music → Direct → Movie). */
fun SoundbarController.cycleEq() {
    val presets = Proto.EQ.values.toList()
    val i = presets.indexOf(controlValues.value[Proto.CHAR_VZ_EQ])   // -1 if unknown → first
    setEq(presets[(i + 1) % presets.size])
}

fun eqLabel(value: Int?): String? = Proto.EQ.entries.firstOrNull { it.value == value }?.key
