package io.github.trevortherrien.unbarred

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * In-memory [SoundbarController] with no Bluetooth. Used by @Preview, the emulator,
 * and UI/unit tests. Records every write so tests can assert what the UI sent.
 */
class FakeSoundbarController : SoundbarController {

    data class Write(val service: UUID, val char: UUID, val value: Int, val label: String)
    val writes = mutableListOf<Write>()

    private val _status = MutableStateFlow("Idle (fake)")
    private val _connected = MutableStateFlow(false)
    private val _phase = MutableStateFlow(LinkPhase.IDLE)
    override val phase: StateFlow<LinkPhase> = _phase.asStateFlow()
    private val _deviceName = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _capabilities = MutableStateFlow(Capabilities())
    private val _deviceState = MutableStateFlow(DeviceState())
    private val _controlValues = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    private val _scanResults = MutableStateFlow<List<BarDevice>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    override val status: StateFlow<String> = _status.asStateFlow()
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    override val events: SharedFlow<String> = _events.asSharedFlow()
    override val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()
    override val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()
    override val controlValues: StateFlow<Map<UUID, Int>> = _controlValues.asStateFlow()
    override val scanResults: StateFlow<List<BarDevice>> = _scanResults.asStateFlow()
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val nicknames = mutableMapOf<String, String?>()

    private val fakeControls: Map<UUID, Int> = mapOf(
        Proto.CHAR_VOLUME to 20, Proto.CHAR_BASS to -3, Proto.CHAR_TREBLE to 2,
        Proto.CHAR_SUBWOOFER to -5, Proto.CHAR_DIALOG to 1, Proto.CHAR_NIGHT_MODE to 0,
        Proto.CHAR_VZ_EQ to 0,
    )

    private val fakeState = DeviceState(
        modelName = "SL512X-0806", serial = "0123456789", firmware = "1.2.3",
        friendlyName = "Living Room Bar", currentInputText = "ARC", currentInputHex = "415243", audioOut = 0,
        speakerStatusHex = "01 01 01", classicStatus = 0,
    )

    override fun scanAndConnect() {
        // Single fake bar → connect immediately (mirrors the real single-bar flow).
        _deviceName.value = "VIZIO Bar (fake)"
        _connected.value = true
        _scanResults.value = emptyList()
        _capabilities.value = Capabilities(ModelProfiles.forModel(fakeState.modelName), emptySet(), fakeState.modelName)
        _deviceState.value = fakeState
        _controlValues.value = fakeControls
        _status.value = "Authenticated ✓ ready (fake)"
        _phase.value = LinkPhase.READY
    }

    override fun connect(address: String) = scanAndConnect()

    override fun rename(address: String, nickname: String?) {
        nicknames[address] = nickname
        _scanResults.value = _scanResults.value.map {
            if (it.address == address) it.copy(nickname = nickname) else it
        }
    }

    override fun disconnect() {
        _connected.value = false
        _deviceState.value = DeviceState()
        _controlValues.value = emptyMap()
        _status.value = "Disconnected (fake)"
        _phase.value = LinkPhase.IDLE
    }

    override fun refreshState() { _deviceState.value = fakeState }

    override fun write(service: UUID, char: UUID, value: Int, label: String) {
        writes += Write(service, char, value, label)
        _controlValues.value = _controlValues.value + (char to value)
        _status.value = "$label → $value ✓ (fake)"
    }
}
