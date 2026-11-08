package io.github.trevortherrien.unbarred

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val vm: SoundbarViewModel by viewModels {
        SoundbarViewModelFactory((application as UnbarredApp).controller)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // Notifications are optional (they only power the quick-controls panel); BLE is required.
        if (neededPerms().all { granted[it] ?: isGranted(it) }) vm.controller.scanAndConnect()
    }

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun optionalPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && AppSettings.notificationControls.value)
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()

    private fun isGranted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun connectOrRequest() {
        val missing = (neededPerms() + optionalPerms()).filterNot(::isGranted)
        if (missing.isEmpty() || neededPerms().all(::isGranted) && missing.all { it in askedOptional }) {
            vm.controller.scanAndConnect()
        } else {
            askedOptional += missing
            permLauncher.launch(missing.toTypedArray())
        }
    }

    /** Optional permissions already asked for this launch, so a "no" doesn't re-prompt every connect. */
    private val askedOptional = mutableSetOf<String>()

    // ---- phone volume keys → bar volume, only while this screen is in front ----
    private fun volumeKeysActive(): Boolean =
        AppSettings.volumeKeys.value &&
            vm.controller.phase.value == LinkPhase.READY &&
            vm.controller.controlValues.value[Proto.CHAR_VOLUME] != null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysActive()) {
            val step = AppSettings.volumeStep.value
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { vm.controller.nudgeVolume(step); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { vm.controller.nudgeVolume(-step); return true }
                KeyEvent.KEYCODE_VOLUME_MUTE -> { if (event.repeatCount == 0) vm.controller.toggleMute(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysActive() && keyCode in VOLUME_KEYS) return true   // swallow so the phone UI stays quiet
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = VizioDarkColors) {
                var accepted by remember { mutableStateOf(Disclaimer.isAccepted(this)) }
                if (!accepted) {
                    DisclaimerScreen(
                        onAccept = { Disclaimer.setAccepted(this); accepted = true },
                        onDecline = { finishAffinity() }
                    )
                } else {
                    Screen(vm.controller, onConnect = ::connectOrRequest)
                }
            }
        }
    }
}

private val VOLUME_KEYS = setOf(
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE
)

/** A restrained "audio gear" dark palette — near-black surfaces, a warm amber accent. */
private val VizioDarkColors = darkColorScheme(
    primary = Color(0xFFFFB950),
    onPrimary = Color(0xFF3A2600),
    secondary = Color(0xFF9CD0FF),
    background = Color(0xFF0E0F12),
    surface = Color(0xFF16181D),
    surfaceContainer = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF232732),
    onSurface = Color(0xFFE6E8EC),
    onSurfaceVariant = Color(0xFF9AA0AC),
    outline = Color(0xFF3A3F4B),
)

@Composable
private fun <T> StateFlow<T>.state(): State<T> = collectAsState()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(controller: SoundbarController, onConnect: () -> Unit) {
    val status by controller.status.state()
    val connected by controller.connected.state()
    val phase by controller.phase.state()
    val name by controller.deviceName.state()
    val caps by controller.capabilities.state()
    val devState by controller.deviceState.state()
    val values by controller.controlValues.state()
    val scanResults by controller.scanResults.state()
    val scanning by controller.scanning.state()

    val context = LocalContext.current
    LaunchedEffect(controller) {
        controller.events.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }
    val showDiagnostics by AppSettings.showDiagnostics.state()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unbarred", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionCard(phase, status, name, onConnect) { controller.disconnect() }

            // Only surface the picker when there's an actual choice (2+ bars). A single bar still
            // auto-connects exactly as before — no new UI in the common case.
            if (!connected && scanResults.size > 1) {
                BarPickerCard(scanResults, scanning,
                    onConnect = { controller.connect(it) },
                    onRename = { addr, nick -> controller.rename(addr, nick) })
            }

            if (connected && devState.isPopulated) {
                DeviceInfoCard(devState, showDiagnostics) { controller.refreshState() }
            }

            val enabled = phase == LinkPhase.READY

            if (phase == LinkPhase.SYNCING) SyncingBanner()

            // Transport: the controls you reach for most.
            TransportCard(enabled, values, controller)

            // Sound profile: the mode selectors.
            SoundProfileCard(enabled, values, devState, controller)

            LedCard(enabled, values, controller)
            LevelsCard(enabled, values, controller, caps)
            EffectsCard(enabled, values, controller)
            SystemCard(enabled, values, controller, caps)

            Text(
                "Controls reflect the bar's live state and follow changes made with the remote. " +
                    "While you drag a control it leads; it re-syncs when you let go.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// --------------------------------------------------------------------------------------
// Live-binding helpers: local state leads while the user interacts, then snaps to the
// bar's reported value when it changes (so the remote / another phone reflect here too).
// --------------------------------------------------------------------------------------

@Composable
private fun LiveLevelSlider(
    label: String, char: UUID, values: Map<UUID, Int>,
    min: Int, max: Int, enabled: Boolean, onChange: (Int) -> Unit
) {
    val barValue = values[char]
    val scope = rememberCoroutineScope()
    var local by remember { mutableFloatStateOf((barValue ?: 0).coerceIn(min, max).toFloat()) }
    var lastSent by remember { mutableIntStateOf(local.toInt()) }
    var interacting by remember { mutableStateOf(false) }

    LaunchedEffect(barValue) {
        if (!interacting && barValue != null && barValue.toFloat() != local) {
            local = barValue.coerceIn(min, max).toFloat(); lastSent = barValue
        }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(if (barValue == null && !interacting) "—" else "${local.toInt()}",
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = local,
            onValueChange = {
                interacting = true
                local = it
                val v = it.toInt()
                if (v != lastSent) { lastSent = v; onChange(v) }
            },
            onValueChangeFinished = {
                onChange(local.toInt())
                // Keep ignoring echoes briefly so a lagging NOTIFY doesn't yank the thumb.
                scope.launch { delay(600); interacting = false }
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            enabled = enabled
        )
    }
}

@Composable
private fun LiveSwitchRow(
    label: String, char: UUID, values: Map<UUID, Int>, enabled: Boolean, onChange: (Boolean) -> Unit
) {
    val barValue = values[char]
    var local by remember { mutableStateOf((barValue ?: 0) == 1) }
    LaunchedEffect(barValue) { if (barValue != null) local = (barValue == 1) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = local, onCheckedChange = { local = it; onChange(it) }, enabled = enabled)
    }
}

/** Segmented single-choice whose current selection is [barValue] (null = nothing highlighted).
 *  Callers pass the live value — straight from [controlValues] for enum controls, or derived
 *  from a reported text token (e.g. the input selector; see [inputSelectionFrom]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveChoiceRow(
    title: String, options: Map<String, Int>, barValue: Int?,
    enabled: Boolean, onPick: (Int) -> Unit
) {
    var selected by remember { mutableStateOf(barValue) }
    LaunchedEffect(barValue) { if (barValue != null) selected = barValue }
    SectionCard(title) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val entries = options.entries.toList()
            entries.forEachIndexed { i, (label, v) ->
                SegmentedButton(
                    selected = selected == v,
                    onClick = { selected = v; onPick(v) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, entries.size)
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// Cards
// --------------------------------------------------------------------------------------

@Composable
private fun ConnectionCard(
    phase: LinkPhase, status: String, name: String?,
    onConnect: () -> Unit, onDisconnect: () -> Unit
) {
    val connected = phase == LinkPhase.READY || phase == LinkPhase.SYNCING
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase.busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    val dot = if (connected) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.outline
                    Surface(color = dot, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(10.dp)) {}
                }
                Text(phase.label, style = MaterialTheme.typography.titleMedium)
                name?.let {
                    Spacer(Modifier.weight(1f))
                    Text(it, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (phase.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !phase.busy, modifier = Modifier.weight(1f)) {
                    Text(if (connected) "Reconnect" else "Scan & Connect")
                }
                OutlinedButton(onClick = onDisconnect, enabled = phase != LinkPhase.IDLE) {
                    Text(if (phase.busy) "Cancel" else "Disconnect")
                }
            }
        }
    }
}

/** Shown while the bar's current values are being read, so nobody edits stale defaults. */
@Composable
private fun SyncingBanner() {
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column {
                Text("Syncing with the bar", style = MaterialTheme.typography.titleSmall)
                Text("Controls unlock once current settings are read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BarPickerCard(
    results: List<BarDevice>, scanning: Boolean,
    onConnect: (String) -> Unit, onRename: (String, String?) -> Unit
) {
    var renaming by remember { mutableStateOf<BarDevice?>(null) }
    SectionCard(if (scanning) "Scanning for bars…" else "Select a bar") {
        if (results.isEmpty()) {
            Text("Looking for sound bars nearby…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            results.forEach { bar ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bar.label)
                        Text("${bar.address} · ${bar.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { renaming = bar }) { Text("Rename") }
                    Button(onClick = { onConnect(bar.address) }) { Text("Connect") }
                }
            }
        }
    }
    renaming?.let { bar ->
        RenameDialog(
            bar = bar,
            onDismiss = { renaming = null },
            onSave = { nick -> onRename(bar.address, nick); renaming = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialog(bar: BarDevice, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(bar.nickname ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this bar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bar.advertisedName ?: bar.address, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Custom name") }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.ifBlank { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onSave(null) }) { Text("Clear") } }
    )
}

@Composable
private fun DeviceInfoCard(st: DeviceState, showDiagnostics: Boolean, onRefresh: () -> Unit) {
    SectionCard("Device") {
        InfoRow("Model", st.modelName)
        InfoRow("Friendly name", st.friendlyName)
        InfoRow("Serial", st.serial)
        InfoRow("Firmware", st.firmware)
        InfoRow("Current input", st.currentInputText)
        if (st.speakerStatusHex != null) InfoRow("Wireless speakers", "0x${st.speakerStatusHex}")
        if (st.classicStatus != null) InfoRow("Bluetooth", st.classicStatus.toString())
        if (showDiagnostics) {
            if (st.lastStatusHex != null) InfoRow("status (F103)", "0x${st.lastStatusHex}")
            if (st.lastErrorHex != null) InfoRow("error (F102)", "0x${st.lastErrorHex}")
            if (st.lastResponseHex != null) InfoRow("response (F101)", "0x${st.lastResponseHex}")
        }
        TextButton(onClick = onRefresh, contentPadding = PaddingValues(0.dp)) { Text("Refresh") }
    }
}

// --------------------------------------------------------------------------------------
// Settings screen
// --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val verbose by AppSettings.verboseLogging.state()
    val live by AppSettings.liveUpdates.state()
    val diagnostics by AppSettings.showDiagnostics.state()
    val volumeKeys by AppSettings.volumeKeys.state()
    val notifControls by AppSettings.notificationControls.state()
    val volumeStep by AppSettings.volumeStep.state()
    val logLines by AppLog.lines.state()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Controls") {
                PlainSwitchRow("Volume keys control the bar",
                    "While Unbarred is open, the phone's volume buttons change the sound bar.", volumeKeys) {
                    AppSettings.volumeKeys.value = it
                }
                PlainSwitchRow("Notification controls",
                    "Volume and EQ buttons in the notification shade while connected. " +
                        "Closes when you disconnect or close the app.", notifControls) {
                    AppSettings.notificationControls.value = it
                }
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Volume step")
                        Text("$volumeStep", fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = volumeStep.toFloat(),
                        onValueChange = { AppSettings.volumeStep.value = it.toInt() },
                        valueRange = 1f..5f, steps = 3
                    )
                }
            }

            SectionCard("Experimental") {
                PlainSwitchRow("Live updates (notifications)",
                    "Track changes made with the remote. Applies on next connect.", live) {
                    AppSettings.liveUpdates.value = it
                }
                PlainSwitchRow("Show diagnostic channels",
                    "Surface the F101–F103 response/error/status values in the Device card.", diagnostics) {
                    AppSettings.showDiagnostics.value = it
                }
            }

            SectionCard("Diagnostic log") {
                PlainSwitchRow("Verbose logging",
                    "Capture every write and notification (not just status changes).", verbose) {
                    AppSettings.verboseLogging.value = it
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Unbarred log")
                                putExtra(android.content.Intent.EXTRA_TEXT, AppLog.exportText())
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share log"))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share log") }
                    OutlinedButton(onClick = { AppLog.clear() }) { Text("Clear") }
                }
                Text("${logLines.size} lines", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(10.dp)
                    ) {
                        if (logLines.isEmpty()) {
                            Text("No log yet — connect to the bar.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            logLines.takeLast(200).forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            UpdatesCard()

            SectionCard("About") {
                Text(Disclaimer.TITLE, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                DisclaimerBody()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun UpdatesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }

    fun open(url: String) {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)))
        }
    }

    SectionCard("Updates") {
        InfoRow("Installed version", version)
        Button(
            enabled = !checking,
            onClick = {
                checking = true; result = null
                scope.launch {
                    result = UpdateChecker.check(version)
                    checking = false
                }
            }
        ) { Text(if (checking) "Checking…" else "Check for updates") }

        when (val r = result) {
            null -> {}
            is UpdateChecker.Result.UpToDate ->
                Text("You're on the latest version.", color = MaterialTheme.colorScheme.primary)
            is UpdateChecker.Result.NotConfigured ->
                Text(r.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            is UpdateChecker.Result.Error ->
                Text("Couldn't check: ${r.message}", color = MaterialTheme.colorScheme.error)
            is UpdateChecker.Result.Available -> {
                Text("Update available: ${r.version}", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                if (r.notes.isNotBlank()) {
                    Text(r.notes.take(600), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.apkUrl != null) {
                        Button(onClick = { open(r.apkUrl) }, modifier = Modifier.weight(1f)) { Text("Download APK") }
                        OutlinedButton(onClick = { open(r.pageUrl) }) { Text("Release") }
                    } else {
                        Button(onClick = { open(r.pageUrl) }, modifier = Modifier.weight(1f)) { Text("View release") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainSwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label.contains("(F10")) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun TransportCard(enabled: Boolean, values: Map<UUID, Int>, controller: SoundbarController) {
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val vol = values[Proto.CHAR_VOLUME]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("${vol ?: "—"}", style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
            LiveLevelSlider("Level", Proto.CHAR_VOLUME, values, 0, 100, enabled) { controller.setVolume(it) }
            LiveSwitchRow("Mute", Proto.CHAR_MUTE, values, enabled) { controller.setMute(it) }
        }
    }
}

@Composable
private fun SoundProfileCard(
    enabled: Boolean, values: Map<UUID, Int>, devState: DeviceState, controller: SoundbarController
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LiveChoiceRow("EQ", Proto.EQ, values[Proto.CHAR_VZ_EQ], enabled) { controller.setEq(it) }
        LiveChoiceRow("Surround (5.1)", Proto.SURROUND, values[Proto.CHAR_SURR_SPEAKERS], enabled) { controller.setSurround(it) }
        LiveChoiceRow("Height aim", Proto.ELEVATE, values[Proto.CHAR_ELEVATE_SPK], enabled) { controller.setElevate(it) }
        // Input reports as a text token (A104, e.g. "ARC"), not the write-enum — map it.
        // A104 is read-only (no NOTIFY); the tap shows optimistically, and the Device card's
        // "Current input" re-reads on manual Refresh / reconnect (avoids a snap-back while the
        // bar takes a second to actually switch).
        LiveChoiceRow("Input", Proto.INPUT, inputSelectionFrom(devState.currentInputText), enabled) {
            controller.setInput(it)
        }
    }
}

/** Map the bar's reported input token (A104) to one of the [Proto.INPUT] choices, or null. */
private fun inputSelectionFrom(text: String?): Int? {
    val t = text?.uppercase() ?: return null
    return when {
        "ARC" in t || "EARC" in t || "HDMI" in t -> Proto.INPUT["HDMI"]
        "USB" in t -> Proto.INPUT["USB"]
        "BT" in t || "BLUE" in t -> Proto.INPUT["Bluetooth"]
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedCard(enabled: Boolean, values: Map<UUID, Int>, controller: SoundbarController) {
    val current = values[Proto.CHAR_ELEVATE_LED]
    SectionCard("LED color") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Proto.LED.forEach { (label, v) ->
                if (current == v) {
                    Button(enabled = enabled, onClick = { controller.setLed(v) }) { Text(label) }
                } else {
                    FilledTonalButton(enabled = enabled, onClick = { controller.setLed(v) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun LevelsCard(enabled: Boolean, values: Map<UUID, Int>, controller: SoundbarController, caps: Capabilities) {
    val lo = caps.profile.levelRange.first
    val hi = caps.profile.levelRange.last
    SectionCard("Levels") {
        LiveLevelSlider("Bass", Proto.CHAR_BASS, values, lo, hi, enabled) { controller.setBass(it) }
        LiveLevelSlider("Treble", Proto.CHAR_TREBLE, values, lo, hi, enabled) { controller.setTreble(it) }
        LiveLevelSlider("Center", Proto.CHAR_CENTER, values, lo, hi, enabled) { controller.setCenter(it) }
        val dlg = caps.profile.dialogRange
        LiveLevelSlider("Clear Dialog", Proto.CHAR_DIALOG, values, dlg.first, dlg.last, enabled) { controller.setDialog(it) }
        LiveLevelSlider("Subwoofer", Proto.CHAR_SUBWOOFER, values, lo, hi, enabled) { controller.setSub(it) }
        LiveLevelSlider("Height", Proto.CHAR_HEIGHT, values, lo, hi, enabled) { controller.setHeight(it) }
        LiveLevelSlider("Surround level", Proto.CHAR_SURR_LEVEL, values, lo, hi, enabled) { controller.setSurrLevel(it) }
        LiveLevelSlider("Surround balance", Proto.CHAR_SURR_BALANCE, values, lo, hi, enabled) { controller.setSurrBalance(it) }
        if (caps.supports(Proto.CHAR_WIDE_SURR_LVL)) {
            LiveLevelSlider("Wide surround level", Proto.CHAR_WIDE_SURR_LVL, values, lo, hi, enabled) { controller.setWideSurrLevel(it) }
        }
        if (caps.supports(Proto.CHAR_WIDE_SURR_BAL)) {
            LiveLevelSlider("Wide surround balance", Proto.CHAR_WIDE_SURR_BAL, values, lo, hi, enabled) { controller.setWideSurrBalance(it) }
        }
    }
}

@Composable
private fun EffectsCard(enabled: Boolean, values: Map<UUID, Int>, controller: SoundbarController) {
    SectionCard("Effects") {
        LiveSwitchRow("Night mode", Proto.CHAR_NIGHT_MODE, values, enabled) { controller.setNight(it) }
        LiveSwitchRow("Virtual:X", Proto.CHAR_VIRTUAL_X, values, enabled) { controller.setVirtualX(it) }
        LiveSwitchRow("TruVolume", Proto.CHAR_TRU_VOLUME, values, enabled) { controller.setTruVolume(it) }
    }
}

@Composable
private fun SystemCard(enabled: Boolean, values: Map<UUID, Int>, controller: SoundbarController, caps: Capabilities) {
    val p = caps.profile
    SectionCard("System") {
        LiveLevelSlider("A/V delay (lip-sync)", Proto.CHAR_AV_DELAY, values, 0, p.avDelayMax, enabled) { controller.setAvDelay(it) }
        if (p.powerLedStates <= 2) {
            LiveSwitchRow("Power LED", Proto.CHAR_POWER_LED, values, enabled) { controller.setPowerLed(if (it) 1 else 0) }
        } else {
            LiveLevelSlider("Power-LED brightness", Proto.CHAR_POWER_LED, values, 0, p.powerLedStates - 1, enabled) { controller.setPowerLed(it) }
        }
        if (p.ecoPowerStates > 2) {
            LiveLevelSlider("Eco power", Proto.CHAR_ECO_POWER, values, 0, p.ecoPowerStates - 1, enabled) { controller.setEcoPower(it) }
        }
        LiveSwitchRow("Auto-HDMI", Proto.CHAR_AUTO_HDMI, values, enabled) { controller.setAutoHdmi(it) }
        LiveSwitchRow("Voice assistant", Proto.CHAR_AUTO_VA, values, enabled) { controller.setAutoVa(it) }
        LiveSwitchRow("Announcements", Proto.CHAR_ANNOUNCEMENT, values, enabled) { controller.setAnnounce(it) }
        // Speaker test is a momentary action, not a persisted state — keep it a plain switch.
        SpeakerTestRow(enabled, controller)
    }
}

@Composable
private fun SpeakerTestRow(enabled: Boolean, controller: SoundbarController) {
    var test by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Speaker test tone")
        Switch(checked = test, onCheckedChange = { test = it; controller.speakerTest(it) }, enabled = enabled)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScreenPreview() {
    MaterialTheme(colorScheme = VizioDarkColors) {
        Screen(FakeSoundbarController().apply { scanAndConnect() }, onConnect = {})
    }
}
