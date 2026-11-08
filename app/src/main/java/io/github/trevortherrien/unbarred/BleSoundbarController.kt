package io.github.trevortherrien.unbarred

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real Bluetooth-LE implementation of [SoundbarController].
 *
 * Lint's `MissingPermission` is suppressed at class level because the BLE calls are
 * spread across callbacks lint can't trace back to the Activity's permission grant.
 * The contract is enforced for real two ways: [scanAndConnect] checks the runtime
 * permissions and refuses with a status message if they're absent, and the UI layer
 * requests them before calling in.
 */
@SuppressLint("MissingPermission")
class BleSoundbarController(private val context: Context) : SoundbarController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val btManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }

    private var gatt: BluetoothGatt? = null
    private val scanner get() = btManager.adapter?.bluetoothLeScanner
    private var secured = false
    private var pairingToken: String? = null

    private val opMutex = Mutex()
    @Volatile private var pending: CancellableContinuation<ByteArray>? = null
    @Volatile private var connecting = false

    @Volatile private var intentionalDisconnect = false

    private val _status = MutableStateFlow("Idle")
    private val _phase = MutableStateFlow(LinkPhase.IDLE)
    private val _connected = MutableStateFlow(false)
    private val _deviceName = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _capabilities = MutableStateFlow(Capabilities())
    private val _deviceState = MutableStateFlow(DeviceState())
    private val _controlValues = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    private val _scanResults = MutableStateFlow<List<BarDevice>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    override val status: StateFlow<String> = _status.asStateFlow()
    override val phase: StateFlow<LinkPhase> = _phase.asStateFlow()
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    override val events: SharedFlow<String> = _events.asSharedFlow()
    override val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()
    override val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()
    override val controlValues: StateFlow<Map<UUID, Int>> = _controlValues.asStateFlow()
    override val scanResults: StateFlow<List<BarDevice>> = _scanResults.asStateFlow()
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    @Volatile private var connectedAddress: String? = null
    @Volatile private var scanGen = 0
    private val found = ConcurrentHashMap<String, ScanResult>()

    /** Every control we read on connect and subscribe to for live updates. Enums/toggles/levels
     *  all read back as their current value; input selection is tracked via A104 text instead. */
    private val controlChars: List<Pair<UUID, UUID>> = listOf(
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_VZ_EQ,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_VOLUME,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_MUTE,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_AV_DELAY,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_POWER_LED,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_ECO_POWER,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_AUTO_HDMI,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_AUTO_VA,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_ANNOUNCEMENT,
        Proto.SERVICE_MAIN_MENU to Proto.CHAR_ELEVATE_LED,
        Proto.SERVICE_LEVELS to Proto.CHAR_BASS,
        Proto.SERVICE_LEVELS to Proto.CHAR_TREBLE,
        Proto.SERVICE_LEVELS to Proto.CHAR_CENTER,
        Proto.SERVICE_LEVELS to Proto.CHAR_DIALOG,
        Proto.SERVICE_LEVELS to Proto.CHAR_SUBWOOFER,
        Proto.SERVICE_LEVELS to Proto.CHAR_HEIGHT,
        Proto.SERVICE_LEVELS to Proto.CHAR_SURR_LEVEL,
        Proto.SERVICE_LEVELS to Proto.CHAR_SURR_BALANCE,
        Proto.SERVICE_EFFECTS to Proto.CHAR_SURR_SPEAKERS,
        Proto.SERVICE_EFFECTS to Proto.CHAR_ELEVATE_SPK,
        Proto.SERVICE_EFFECTS to Proto.CHAR_NIGHT_MODE,
        Proto.SERVICE_EFFECTS to Proto.CHAR_VIRTUAL_X,
        Proto.SERVICE_EFFECTS to Proto.CHAR_TRU_VOLUME,
    )

    @Volatile private var discoveredChars: Set<UUID> = emptySet()

    // Coalescing control-write queue: latest value per characteristic wins, so dragging a
    // slider doesn't back up a queue; a single writer drains it with retry + light spacing.
    private data class Target(val service: UUID, val value: Int, val label: String)
    private val targets = ConcurrentHashMap<UUID, Target>()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)

    init { startWriter() }

    private fun log(s: String) { _status.value = s; AppLog.i(TAG, s); AppLog.add(s) }
    private fun emitEvent(msg: String) { _events.tryEmit(msg); AppLog.w(TAG, "EVENT: $msg"); AppLog.add("⚠ $msg") }

    companion object {
        const val TAG = "Unbarred"
        const val MAX_RETRIES = 3

        /** Human-readable name for an Android GATT status code. */
        fun gattStatusName(code: Int): String = when (code) {
            0 -> "SUCCESS(0)"
            1 -> "INVALID_HANDLE(1)"
            2 -> "READ_NOT_PERMITTED(2)"
            3 -> "WRITE_NOT_PERMITTED(3)"
            5 -> "INSUFFICIENT_AUTHENTICATION(5)"
            6 -> "REQUEST_NOT_SUPPORTED(6)"
            7 -> "INVALID_OFFSET(7)"
            8 -> "INSUFFICIENT_AUTHORIZATION(8)"
            13 -> "INVALID_ATTRIBUTE_LENGTH(13)"
            14 -> "ERR_UNLIKELY(14/0x0E) — bar rejected/busy; often too many rapid writes"
            15 -> "INSUFFICIENT_ENCRYPTION(15) — needs bonding"
            133 -> "GATT_ERROR(133/0x85) — generic stack error"
            143 -> "CONNECTION_CONGESTED(143)"
            257 -> "FAILURE(257)"
            else -> "status $code"
        }
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it) }

    private fun propNames(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESP")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }.joinToString("|").ifEmpty { "none" }

    private fun hasBlePermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    // ---------------- scan ----------------
    /** A scan hit is a bar if it advertises our service, or its name says VIZIO (open-scan case). */
    private fun isLikelyBar(r: ScanResult): Boolean =
        r.scanRecord?.serviceUuids?.any { it.uuid == Proto.SERVICE_INFO } == true ||
            r.device.name?.contains("VIZIO", ignoreCase = true) == true

    private fun barFrom(r: ScanResult): BarDevice {
        val addr = r.device.address
        return BarDevice(addr, r.device.name, r.rssi, BarNames.get(context, addr))
    }

    private fun publishResults() {
        _scanResults.value = found.values.map { barFrom(it) }.sortedByDescending { it.rssi }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isLikelyBar(result)) return          // ignore non-VIZIO adverts (open-scan safety)
            found[result.device.address] = result
            publishResults()
        }
        override fun onScanFailed(errorCode: Int) { log("Scan failed: $errorCode"); _scanning.value = false }
    }

    private fun stopScanSafe() { runCatching { scanner?.stopScan(scanCb) } }

    override fun scanAndConnect() {
        if (!hasBlePermissions()) {
            log("Missing Bluetooth permission — grant it and retry."); emitEvent("Bluetooth permission needed"); return
        }
        val s = scanner ?: run { log("Bluetooth off?"); emitEvent("Bluetooth is off"); return }
        intentionalDisconnect = false
        found.clear(); _scanResults.value = emptyList(); _scanning.value = true
        _phase.value = LinkPhase.SCANNING
        val gen = ++scanGen
        log("Scanning for sound bars…")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE_INFO)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { s.startScan(listOf(filter), settings, scanCb) }
        scope.launch {
            // Brief enumeration window. One bar → connect (preserves the single-bar flow); several
            // → leave a picker. If nothing on the filtered scan, widen to an open scan once.
            // 1.5 s is long enough to catch a second advertiser (adverts arrive every ~100–500 ms)
            // while keeping single-bar connect quick.
            delay(1500.milliseconds)
            if (gen != scanGen || connecting || _connected.value) return@launch
            if (found.isEmpty()) {
                stopScanSafe()
                log("No bars via service filter — widening scan…")
                runCatching { s.startScan(null, settings, scanCb) }
                delay(4.seconds)
                if (gen != scanGen || connecting || _connected.value) return@launch
            }
            stopScanSafe()
            _scanning.value = false
            val results = _scanResults.value
            if (results.size != 1) _phase.value = LinkPhase.IDLE
            when (results.size) {
                0 -> { log("No sound bar found"); emitEvent("No sound bar found — is it powered on and in range?") }
                1 -> connect(results.first().address)
                else -> log("${results.size} bars found — pick one")
            }
        }
    }

    override fun connect(address: String) {
        if (!hasBlePermissions()) { emitEvent("Bluetooth permission needed"); return }
        scanGen++                 // cancel any pending scan-decision coroutine
        stopScanSafe()
        _scanning.value = false
        val dev = runCatching { btManager.adapter?.getRemoteDevice(address) }.getOrNull()
        if (dev == null) { _phase.value = LinkPhase.IDLE; log("Couldn't open $address"); emitEvent("Couldn't open that device"); return }
        intentionalDisconnect = false
        connecting = true
        connectedAddress = address
        _phase.value = LinkPhase.CONNECTING
        _deviceName.value = BarNames.get(context, address) ?: dev.name ?: address
        log("Connecting to ${_deviceName.value}…")
        gatt = runCatching { dev.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) { connecting = false; connectedAddress = null; _phase.value = LinkPhase.IDLE; emitEvent("Connection failed to start") }
    }

    override fun rename(address: String, nickname: String?) {
        BarNames.set(context, address, nickname)
        val applied = BarNames.get(context, address)
        _scanResults.value = _scanResults.value.map {
            if (it.address == address) it.copy(nickname = applied) else it
        }
        if (connectedAddress == address) {
            _deviceName.value = applied ?: _deviceName.value
        }
    }

    override fun disconnect() {
        intentionalDisconnect = true
        scanGen++; stopScanSafe(); _scanning.value = false
        targets.clear()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; connecting = false; connectedAddress = null; _connected.value = false; pairingToken = null
        _phase.value = LinkPhase.IDLE
        _deviceState.value = DeviceState(); _controlValues.value = emptyMap(); log("Disconnected")
    }

    // ---------------- gatt callback ----------------
    private val gattCb = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            AppLog.d(TAG, "onConnectionStateChange newState=$newState status=${gattStatusName(statusCode)}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected — discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasIntentional = intentionalDisconnect
                intentionalDisconnect = false
                connecting = false
                connectedAddress = null
                _connected.value = false
                _phase.value = LinkPhase.IDLE
                targets.clear()
                _controlValues.value = emptyMap()
                _deviceState.value = DeviceState()
                log("Link dropped by peer (bar boots unauthenticated clients fast).")
                if (!wasIntentional) emitEvent("Disconnected — the bar dropped the link")
                failPending(RuntimeException("disconnected"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            secured = g.getService(Proto.SERVICE_SECURITY) != null
            val chars = g.services.flatMap { it.characteristics }
            discoveredChars = chars.map { it.uuid }.toSet()
            // Log each VIZIO characteristic's write properties — tells us whether to use
            // Write-Without-Response (no server ack → no 0x0E) or a plain Write Request.
            chars.filter { it.uuid.toString().contains("37619c1") }.forEach {
                AppLog.d(TAG, "CHAR …${it.uuid.toString().takeLast(4)}  props=${propNames(it.properties)}")
            }
            scope.launch { runHandshakeThenReady() }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int
        ) { settlePending(statusCode, value) }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, statusCode: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                settlePending(statusCode, ch.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, statusCode: Int
        ) { settlePending(statusCode, ByteArray(0)) }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int
        ) { settlePending(statusCode, ByteArray(0)) }

        // Push notifications from the bar (NOTIFY). The vendor app never subscribes, but every
        // control characteristic advertises NOTIFY — so this is how we'd learn of changes made
        // by the IR remote / physical buttons / another controller. Spike: log only for now.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) { onNotify(ch.uuid, value) }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotify(ch.uuid, ch.value ?: ByteArray(0))
        }
    }

    private fun onNotify(char: UUID, value: ByteArray) {
        val signed = value.firstOrNull()?.toInt()   // Byte.toInt() sign-extends: 0xf6 -> -10
        AppLog.i(TAG, "NOTIFY …${char.toString().takeLast(4)} = [${value.toHex()}]${signed?.let { " ($it)" } ?: ""}")
        AppLog.verbose("NOTIFY …${char.toString().takeLast(4)} = ${signed ?: "[${value.toHex()}]"}")
        when (char) {
            Proto.CHAR_STATUS   -> _deviceState.value = _deviceState.value.copy(lastStatusHex = value.toHex().ifEmpty { null })
            Proto.CHAR_ERROR    -> _deviceState.value = _deviceState.value.copy(lastErrorHex = value.toHex().ifEmpty { null })
            Proto.CHAR_RESPONSE -> _deviceState.value = _deviceState.value.copy(lastResponseHex = value.toHex().ifEmpty { null })
            else -> if (signed != null) _controlValues.update { it + (char to signed) }
        }
    }

    private fun resumePending(v: ByteArray) { val p = pending; pending = null; p?.resume(v) }
    private fun failPending(e: Throwable) { val p = pending; pending = null; p?.resumeWithException(e) }
    private fun settlePending(statusCode: Int, v: ByteArray) {
        if (statusCode == BluetoothGatt.GATT_SUCCESS) {
            resumePending(v)
        } else {
            AppLog.w(TAG, "GATT op failed: ${gattStatusName(statusCode)}")
            failPending(RuntimeException("GATT op failed (${gattStatusName(statusCode)})"))
        }
    }

    // ---------------- serialized GATT ops ----------------
    private suspend fun writeChar(
        service: UUID, char: UUID, bytes: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ) = opMutex.withLock {
        val g = gatt ?: throw IllegalStateException("no gatt")
        val ch = g.getService(service)?.getCharacteristic(char)
            ?: throw IllegalStateException("char $char missing")
        withTimeout(6.seconds) {
            suspendCancellableCoroutine { cont ->
                pending = cont
                cont.invokeOnCancellation { if (pending === cont) pending = null }  // clear on timeout
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, bytes, writeType)
                } else {
                    @Suppress("DEPRECATION") run { ch.writeType = writeType; ch.value = bytes; g.writeCharacteristic(ch) }
                }
            }
        }
    }

    private suspend fun readChar(service: UUID, char: UUID): ByteArray = opMutex.withLock {
        val g = gatt ?: throw IllegalStateException("no gatt")
        val ch = g.getService(service)?.getCharacteristic(char)
            ?: throw IllegalStateException("char $char missing")
        withTimeout(6.seconds) {
            suspendCancellableCoroutine { cont ->
                pending = cont
                cont.invokeOnCancellation { if (pending === cont) pending = null }  // clear on timeout
                g.readCharacteristic(ch)
            }
        }
    }

    // ---------------- handshake (local, no account) ----------------
    private suspend fun runHandshakeThenReady() {
        try {
            if (secured) {
                // Some stacks aren't ready for the very first GATT op right after service
                // discovery — a brief settle plus a retry turns the occasional first-connect
                // timeout into a reliable connect without a full rescan.
                _phase.value = LinkPhase.AUTHENTICATING
                delay(200.milliseconds)
                authenticateWithRetry()
            } else {
                log("No security service — raw writes, ready")
            }
            // Mark connected first so the UI reflects the link while the state reads run.
            _connected.value = true
            _scanResults.value = emptyList()   // dismiss the picker
            _phase.value = LinkPhase.SYNCING
            refreshStateInternal()
            hydrateControlValues()
            subscribeAll()
            val modelName = _deviceState.value.modelName
            val profile = ModelProfiles.forModel(modelName)
            _capabilities.value = Capabilities(profile, discoveredChars, modelName)
            connecting = false
            _phase.value = LinkPhase.READY
            AppLog.i(TAG, "Model='${modelName ?: "?"}' → profile '${profile.displayName}' (${discoveredChars.size} chars)")
        } catch (e: Exception) {
            log("Handshake failed: ${e.message}")
            emitEvent("Authentication failed: ${e.message}")
            _connected.value = false
            _phase.value = LinkPhase.IDLE
        }
    }

    private suspend fun authenticateWithRetry() {
        val attempts = 3
        var last: Exception? = null
        for (i in 1..attempts) {
            if (gatt == null) throw IllegalStateException("link dropped before auth")
            try {
                authenticateOnce(); return
            } catch (e: Exception) {
                last = e
                log("Auth attempt $i/$attempts failed: ${e.message}")
                if (gatt == null) throw e          // link gone — retrying is futile
                if (i < attempts) delay(400.milliseconds)
            }
        }
        throw last ?: IllegalStateException("authentication failed")
    }

    private suspend fun authenticateOnce() {
        log("Security service present — authenticating…")
        try {
            val gen = readChar(Proto.SERVICE_INFO, Proto.CHAR_SECURITY_GEN)
            log("Gen code = ${gen.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
        } catch (_: Exception) {}

        val dev = Handshake.DEVICE_ID.toByteArray(Charsets.US_ASCII)
        writeChar(Proto.SERVICE_SECURITY, Proto.CHAR_SEC_HANDSHAKE, "1:".toByteArray() + dev)
        delay(120.milliseconds)
        val nonceRaw = readChar(Proto.SERVICE_SECURITY, Proto.CHAR_SEC_HANDSHAKE)
        val nonce = nonceRaw.takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.US_ASCII).trim()
        writeChar(Proto.SERVICE_SECURITY, Proto.CHAR_SEC_PRESHARED,
            ("$nonce:").toByteArray(Charsets.US_ASCII) + Handshake.RESPONSE)
        delay(120.milliseconds)
        val tokRaw = readChar(Proto.SERVICE_SECURITY, Proto.CHAR_SEC_PRESHARED)
        pairingToken = tokRaw.takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.US_ASCII).trim()
        log("Authenticated ✓  ready")
    }

    // ---------------- device state reads (on connect + on demand) ----------------
    override fun refreshState() {
        if (gatt == null) return
        scope.launch { try { refreshStateInternal() } catch (e: Exception) { AppLog.w(TAG, "refreshState: ${e.message}") } }
    }

    /** Reads every reported value best-effort; a missing/unsupported char just yields null. */
    private suspend fun refreshStateInternal() {
        val st = DeviceState(
            modelName = readText(Proto.SERVICE_INFO, Proto.CHAR_MODEL_NAME),
            serial = readText(Proto.SERVICE_INFO, Proto.CHAR_SERIAL),
            firmware = readText(Proto.SERVICE_INFO, Proto.CHAR_FW_VERSION),
            friendlyName = readText(Proto.SERVICE_INFO, Proto.CHAR_FRIENDLY_NAME),
            currentInputText = readText(Proto.SERVICE_INFO, Proto.CHAR_CURRENT_INPUT),
            currentInputHex = readHex(Proto.SERVICE_INFO, Proto.CHAR_CURRENT_INPUT),
            audioOut = readByte(Proto.SERVICE_INFO, Proto.CHAR_AUDIO_OUT),
            discoveredInputsHex = readHex(Proto.SERVICE_MAIN_MENU, Proto.CHAR_DISCOVERED_INPUTS),
            speakerStatusHex = readHex(Proto.SERVICE_LEVELS, Proto.CHAR_WLESS_SPK_ST),
            classicStatus = readByte(Proto.SERVICE_MAIN_MENU, Proto.CHAR_CLASSIC_STATUS),
            lastResponseHex = readHex(Proto.SERVICE_SECURITY, Proto.CHAR_RESPONSE),
            lastErrorHex = readHex(Proto.SERVICE_SECURITY, Proto.CHAR_ERROR),
            lastStatusHex = readHex(Proto.SERVICE_SECURITY, Proto.CHAR_STATUS),
        )
        _deviceState.value = st
        AppLog.i(TAG, "DeviceState model=${st.modelName} serial=${st.serial} fw=${st.firmware} " +
            "name=${st.friendlyName} input='${st.currentInputText}'(0x${st.currentInputHex}) audioOut=${st.audioOut} " +
            "spk=${st.speakerStatusHex} classic=${st.classicStatus} " +
            "resp=${st.lastResponseHex} err=${st.lastErrorHex} status=${st.lastStatusHex}")
    }

    // ---------------- live state: read-all + subscribe-all ----------------
    /** Read every control's current value once, so the UI hydrates to the bar's real state. */
    private suspend fun hydrateControlValues() {
        val map = HashMap<UUID, Int>()
        for ((s, c) in controlChars) {
            val v = readSignedByte(s, c) ?: continue
            map[c] = v
        }
        _controlValues.value = map
        val summary = map.entries.joinToString { "${it.key.toString().takeLast(4)}=${it.value}" }
        AppLog.i(TAG, "ControlValues $summary")
        AppLog.add("read ${map.size} control values")
    }

    /** Subscribe to every control (for live updates incl. external changes) plus the F-channels. */
    private suspend fun subscribeAll() {
        if (!AppSettings.liveUpdates.value) {
            log("Live updates off — not subscribing"); return
        }
        val targets = controlChars + listOf(
            Proto.SERVICE_SECURITY to Proto.CHAR_STATUS,
            Proto.SERVICE_SECURITY to Proto.CHAR_ERROR,
            Proto.SERVICE_SECURITY to Proto.CHAR_RESPONSE,
        )
        var ok = 0
        for ((s, c) in targets) if (enableNotify(s, c)) ok++
        AppLog.i(TAG, "subscribed $ok/${targets.size} characteristics")
        AppLog.add("subscribed $ok/${targets.size} characteristics")
    }

    /** First byte as a SIGNED Int (levels: 0xf6 -> -10), or null if unreadable. */
    private suspend fun readSignedByte(service: UUID, char: UUID): Int? = try {
        readChar(service, char).firstOrNull()?.toInt()
    } catch (_: Exception) { null }

    /** Enable NOTIFY on one characteristic by writing its CCCD (0x2902). */
    private suspend fun enableNotify(service: UUID, char: UUID): Boolean = opMutex.withLock {
        val g = gatt ?: return@withLock false
        val ch = g.getService(service)?.getCharacteristic(char) ?: return@withLock false
        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) return@withLock false
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(Proto.CCCD) ?: return@withLock false
        try {
            withTimeout(4.seconds) {
                suspendCancellableCoroutine { cont ->
                    pending = cont
                    cont.invokeOnCancellation { if (pending === cont) pending = null }  // clear on timeout
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION") run {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(cccd)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "notify enable failed …${char.toString().takeLast(4)}: ${e.message}"); false
        }
    }

    /** NUL-terminated ASCII text, or null if empty/unreadable. */
    private suspend fun readText(service: UUID, char: UUID): String? = try {
        readChar(service, char).takeWhile { it.toInt() != 0 }
            .toByteArray().toString(Charsets.US_ASCII).trim().ifEmpty { null }
    } catch (_: Exception) { null }

    /** First byte as an unsigned Int, or null if unreadable. */
    private suspend fun readByte(service: UUID, char: UUID): Int? = try {
        readChar(service, char).firstOrNull()?.toInt()?.and(0xFF)
    } catch (_: Exception) { null }

    /** Whole value as hex, or null if empty/unreadable (for blobs we don't yet decode). */
    private suspend fun readHex(service: UUID, char: UUID): String? = try {
        readChar(service, char).takeIf { it.isNotEmpty() }?.toHex()
    } catch (_: Exception) { null }

    // ---------------- control writes (coalesced + retried) ----------------
    override fun write(service: UUID, char: UUID, value: Int, label: String) {
        // Coalesce: keep only the latest value per characteristic, then wake the writer.
        targets[char] = Target(service, value, label)
        // Reflect the intent immediately so relative controls (volume keys, notification buttons)
        // step from what was just sent; the NOTIFY echo confirms or corrects it.
        if (_connected.value) _controlValues.update { it + (char to value) }
        writeSignal.trySend(Unit)
    }

    private fun startWriter() = scope.launch {
        for (ignored in writeSignal) {
            while (true) {
                val char = targets.keys.firstOrNull() ?: break
                val t = targets.remove(char) ?: continue
                writeControlWithRetry(t.service, char, t.value, t.label)
                delay(40.milliseconds) // light spacing so we don't congest the link
            }
        }
    }

    private suspend fun writeControlWithRetry(service: UUID, char: UUID, value: Int, label: String) {
        val bytes = Framing.frame(pairingToken, secured, value)
        val ch = gatt?.getService(service)?.getCharacteristic(char)
        // Prefer Write-Without-Response when the characteristic allows it: there is no server
        // ack to come back as status 14 (0x0E), and it's faster for live dragging.
        val noResp = ch != null && (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                   else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        AppLog.d(TAG, "WRITE $label …${char.toString().takeLast(4)} value=$value " +
                "type=${if (noResp) "CMD/no-resp" else "REQ/with-resp"} bytes=[${bytes.toHex()}]")
        AppLog.verbose("WRITE $label=$value ${if (noResp) "cmd" else "req"}")
        repeat(MAX_RETRIES) { attempt ->
            try {
                writeChar(service, char, bytes, type)
                log("$label → $value ✓")
                return
            } catch (e: Exception) {
                AppLog.w(TAG, "WRITE $label attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}")
                if (attempt < MAX_RETRIES - 1) delay(200.milliseconds)
            }
        }
        log("$label failed after $MAX_RETRIES tries")
        emitEvent("$label failed after $MAX_RETRIES tries")
    }
}
