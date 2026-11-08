package io.github.trevortherrien.unbarred

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    SOUND("Sound", R.drawable.ic_ui_sound),
    LEVELS("Levels", R.drawable.ic_ui_settings),
    SYSTEM("System", R.drawable.ic_ui_bar),
}

/** How long a second Back press counts as "exit" after the first. */
private const val EXIT_WINDOW_MS = 2000L

/**
 * The whole app UI. Back navigation: Settings → main; Levels/System → Sound; on Sound, Back twice
 * within [EXIT_WINDOW_MS] calls [onExit] (the first press shows a toast).
 */
@Composable
fun Screen(controller: SoundbarController, onConnect: () -> Unit, onExit: () -> Unit = {}) {
    val status by controller.status.state()
    val phase by controller.phase.state()
    val name by controller.deviceName.state()
    val caps by controller.capabilities.state()
    val devState by controller.deviceState.state()
    val values by controller.controlValues.state()
    val scanResults by controller.scanResults.state()
    val scanning by controller.scanning.state()
    val showDiagnostics by AppSettings.showDiagnostics.state()

    val context = LocalContext.current
    LaunchedEffect(controller) {
        controller.events.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.SOUND) }
    var lastBackAt by remember { mutableLongStateOf(0L) }

    BackHandler {
        when {
            showSettings -> showSettings = false
            tab != Tab.SOUND -> tab = Tab.SOUND
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackAt < EXIT_WINDOW_MS) {
                    onExit()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    val linked = phase == LinkPhase.READY || phase == LinkPhase.SYNCING
    val enabled = phase == LinkPhase.READY

    Box(Modifier.fillMaxSize().background(Ub.Background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(if (linked) name else null, phase, devState.modelName, onSettings = { showSettings = true })

            if (!linked) {
                ConnectView(phase, status, scanResults, scanning, onConnect,
                    onCancel = { controller.disconnect() },
                    onPick = { controller.connect(it) },
                    onRename = { addr, nick -> controller.rename(addr, nick) })
            } else {
                key(tab) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            // Clear the floating tab bar: its 64dp + 16dp margins, the system
                            // navigation bar inset under it, plus breathing room.
                            .navigationBarsPadding()
                            .padding(top = 4.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (phase == LinkPhase.SYNCING) SyncingBanner()
                        when (tab) {
                            Tab.SOUND -> SoundTab(values, enabled, controller)
                            Tab.LEVELS -> LevelsTab(values, enabled, controller, caps)
                            Tab.SYSTEM -> SystemTab(values, enabled, controller, caps, devState, showDiagnostics, name)
                        }
                    }
                }
            }
        }
        if (linked) {
            BottomNav(tab, onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

// --------------------------------------------------------------------------------------
// Chrome
// --------------------------------------------------------------------------------------

@Composable
private fun Header(name: String?, phase: LinkPhase, model: String?, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name ?: "Unbarred", style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase.busy) {
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = Ub.Accent)
                } else {
                    Box(Modifier.size(8.dp).background(
                        if (phase == LinkPhase.READY) Ub.Accent else Ub.Outline, CircleShape))
                }
                Text(phase.label, fontSize = 13.sp, color = Ub.Muted)
                if (phase == LinkPhase.READY && model != null) {
                    Text("· $model", fontSize = 13.sp, color = Ub.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        RoundIconButton(R.drawable.ic_ui_gear, "Settings", onSettings, size = 44)
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(16.dp).fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(32.dp),
        color = Ub.Surface,
        border = BorderStroke(1.dp, Ub.Track),
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tab.entries.forEach { t ->
                val on = t == current
                Surface(
                    onClick = { onSelect(t) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(24.dp),
                    color = if (on) Ub.Accent else Color.Transparent,
                    contentColor = if (on) Ub.OnAccent else Ub.Muted,
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(t.icon), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Shown while the bar's current values are being read, so nobody edits stale defaults. */
@Composable
private fun SyncingBanner() {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ub.Accent)
            Column {
                Text("Syncing with the bar", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Controls unlock once current settings are read.", fontSize = 13.sp, color = Ub.Muted)
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// Not connected / connecting
// --------------------------------------------------------------------------------------

@Composable
private fun ConnectView(
    phase: LinkPhase, status: String, results: List<BarDevice>, scanning: Boolean,
    onConnect: () -> Unit, onCancel: () -> Unit,
    onPick: (String) -> Unit, onRename: (String, String?) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Find your\nsound bar", fontSize = 44.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-1.5).sp, lineHeight = 46.sp)
        Text("Turn the bar on and keep your phone nearby. Unbarred talks to it directly over " +
            "Bluetooth — no account, no cloud.", fontSize = 15.sp, color = Ub.Muted)

        Text(status, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ub.Muted)
        if (phase.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ub.Accent, trackColor = Ub.Track)
        }

        Button(onClick = onConnect, enabled = !phase.busy, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)) {
            Text("Scan & Connect", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        if (phase != LinkPhase.IDLE) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Ub.Outline)) {
                Text("Cancel", color = Ub.Text)
            }
        }

        // Only when there's an actual choice (2+ bars); one bar auto-connects.
        if (results.size > 1) BarPicker(results, scanning, onPick, onRename)
    }
}

@Composable
private fun BarPicker(
    results: List<BarDevice>, scanning: Boolean,
    onConnect: (String) -> Unit, onRename: (String, String?) -> Unit,
) {
    var renaming by remember { mutableStateOf<BarDevice?>(null) }
    SectionLabel(if (scanning) "Scanning for bars…" else "Select a bar", Modifier.padding(top = 8.dp))
    Panel {
        results.forEach { bar ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bar.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${bar.address} · ${bar.rssi} dBm", fontSize = 12.sp, color = Ub.Muted)
                }
                TextButton(onClick = { renaming = bar }) { Text("Rename", color = Ub.Muted) }
                Button(onClick = { onConnect(bar.address) }) { Text("Connect") }
            }
        }
    }
    renaming?.let { bar ->
        RenameDialog(bar.nickname, bar.osAlias ?: bar.advertisedName ?: bar.address, onDismiss = { renaming = null },
            onSave = { nick -> onRename(bar.address, nick); renaming = null })
    }
}

@Composable
private fun RenameDialog(current: String?, subtitle: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ub.SurfaceHigh,
        title = { Text("Name this bar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Ub.Muted)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Custom name") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.ifBlank { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onSave(null) }) { Text("Clear", color = Ub.Muted) } }
    )
}

// --------------------------------------------------------------------------------------
// Tabs
// --------------------------------------------------------------------------------------

@Composable
private fun SoundTab(values: Map<UUID, Int>, enabled: Boolean, controller: SoundbarController) {
    // Sized to fit a typical phone screen without scrolling (scroll remains as a fallback).
    VolumeHero(values, enabled, controller)

    SectionLabel("Sound mode", Modifier.padding(top = 6.dp))
    LiveChoice(Proto.EQ, values[Proto.CHAR_VZ_EQ], enabled, ChoiceStyle.LARGE) { controller.setEq(it) }

    SectionLabel("Surround", Modifier.padding(top = 6.dp))
    LiveChoice(Proto.SURROUND, values[Proto.CHAR_SURR_SPEAKERS], enabled, ChoiceStyle.COMPACT) { controller.setSurround(it) }

    SectionLabel("Height speakers", Modifier.padding(top = 6.dp))
    LiveChoice(Proto.ELEVATE, values[Proto.CHAR_ELEVATE_SPK], enabled, ChoiceStyle.COMPACT) { controller.setElevate(it) }

    SectionLabel("Effects", Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveToggleTile("Night", R.drawable.ic_ui_moon, Proto.CHAR_NIGHT_MODE, values, enabled,
            Modifier.weight(1f)) { controller.setNight(it) }
        LiveToggleTile("Virtual:X", R.drawable.ic_ui_wave, Proto.CHAR_VIRTUAL_X, values, enabled,
            Modifier.weight(1f)) { controller.setVirtualX(it) }
        LiveToggleTile("TruVolume", R.drawable.ic_ui_level, Proto.CHAR_TRU_VOLUME, values, enabled,
            Modifier.weight(1f)) { controller.setTruVolume(it) }
    }
}

@Composable
private fun VolumeHero(values: Map<UUID, Int>, enabled: Boolean, controller: SoundbarController) {
    val volume = values[Proto.CHAR_VOLUME]
    val muted = values[Proto.CHAR_MUTE] == 1
    val step = AppSettings.volumeStep.state().value

    SectionLabel(if (muted) "Volume · muted" else "Volume", Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(volume?.toString() ?: "—", style = HeroNumberStyle, color = if (muted) Ub.Muted else Ub.Text)
            Text(" / $VOLUME_MAX", fontSize = 18.sp, color = Ub.Muted, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(if (muted) R.drawable.ic_ui_mute else R.drawable.ic_ui_sound,
                if (muted) "Unmute" else "Mute", { controller.setMute(!muted) },
                enabled = enabled, highlighted = muted)
            RoundIconButton(R.drawable.ic_ui_remove, "Volume down", { controller.nudgeVolume(-step) }, enabled = enabled)
            RoundIconButton(R.drawable.ic_ui_add, "Volume up", { controller.nudgeVolume(step) }, filled = true, enabled = enabled)
        }
    }
    LiveLevelSlider("Volume", Proto.CHAR_VOLUME, values, 0, VOLUME_MAX, enabled, showHeader = false) {
        controller.setVolume(it)
    }
}

@Composable
private fun LevelsTab(values: Map<UUID, Int>, enabled: Boolean, controller: SoundbarController, caps: Capabilities) {
    val lo = caps.profile.levelRange.first
    val hi = caps.profile.levelRange.last
    val dlg = caps.profile.dialogRange
    val mode = eqLabel(values[Proto.CHAR_VZ_EQ])

    SectionLabel(if (mode != null) "Levels · $mode" else "Levels", Modifier.padding(top = 8.dp)) {
        Text("Saved per sound mode", fontSize = 12.sp, color = Ub.Muted)
    }
    Panel {
        LiveLevelSlider("Bass", Proto.CHAR_BASS, values, lo, hi, enabled) { controller.setBass(it) }
        LiveLevelSlider("Treble", Proto.CHAR_TREBLE, values, lo, hi, enabled) { controller.setTreble(it) }
        LiveLevelSlider("Center", Proto.CHAR_CENTER, values, lo, hi, enabled) { controller.setCenter(it) }
        LiveLevelSlider("Clear Dialog", Proto.CHAR_DIALOG, values, dlg.first, dlg.last, enabled) { controller.setDialog(it) }
    }
    SectionLabel("Speakers", Modifier.padding(top = 12.dp))
    Panel {
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
private fun SystemTab(
    values: Map<UUID, Int>, enabled: Boolean, controller: SoundbarController, caps: Capabilities,
    st: DeviceState, showDiagnostics: Boolean, shownName: String?,
) {
    val p = caps.profile
    var renaming by remember { mutableStateOf(false) }

    SectionLabel("Input", Modifier.padding(top = 8.dp))
    // Input reports as a text token (A104, e.g. "ARC"), not the write-enum — map it. A104 has no
    // NOTIFY; the tap shows optimistically and the device info re-reads on Refresh / reconnect.
    LiveChoice(Proto.INPUT, inputSelectionFrom(st.currentInputText), enabled, ChoiceStyle.COMPACT) { controller.setInput(it) }

    SectionLabel("Audio", Modifier.padding(top = 12.dp))
    Panel {
        LiveLevelSlider("A/V delay (lip-sync)", Proto.CHAR_AV_DELAY, values, 0, p.avDelayMax, enabled) { controller.setAvDelay(it) }
        SpeakerTestRow()
    }

    SectionLabel("Lighting", Modifier.padding(top = 12.dp))
    Panel {
        if (p.powerLedStates <= 2) {
            LiveSwitchRow("Power LED", Proto.CHAR_POWER_LED, values, enabled) { controller.setPowerLed(if (it) 1 else 0) }
        } else {
            LiveLevelSlider("Power-LED brightness", Proto.CHAR_POWER_LED, values, 0, p.powerLedStates - 1, enabled) { controller.setPowerLed(it) }
        }
        Text("Elevate LED color", fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        LiveChoice(Proto.LED, values[Proto.CHAR_ELEVATE_LED], enabled) { controller.setLed(it) }
        Spacer(Modifier.height(6.dp))
    }

    SectionLabel("Power & assistant", Modifier.padding(top = 12.dp))
    Panel {
        if (p.ecoPowerStates > 2) {
            LiveLevelSlider("Eco power", Proto.CHAR_ECO_POWER, values, 0, p.ecoPowerStates - 1, enabled) { controller.setEcoPower(it) }
        }
        LiveSwitchRow("Auto-HDMI", Proto.CHAR_AUTO_HDMI, values, enabled) { controller.setAutoHdmi(it) }
        LiveSwitchRow("Voice assistant", Proto.CHAR_AUTO_VA, values, enabled) { controller.setAutoVa(it) }
        LiveSwitchRow("Announcements", Proto.CHAR_ANNOUNCEMENT, values, enabled) { controller.setAnnounce(it) }
    }

    SectionLabel("Device", Modifier.padding(top = 12.dp))
    Panel {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shown as", fontSize = 13.sp, color = Ub.Muted)
                Text(shownName ?: "—", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { renaming = true }, modifier = Modifier.height(44.dp),
                border = BorderStroke(1.dp, Ub.Outline)) { Text("Rename", color = Ub.Text) }
        }
        InfoRow("Model", st.modelName)
        InfoRow("Name", st.friendlyName)
        InfoRow("Firmware", st.firmware)
        InfoRow("Serial", st.serial)
        InfoRow("Current input", st.currentInputText)
        if (st.speakerStatusHex != null) InfoRow("Wireless speakers", "0x${st.speakerStatusHex}")
        if (st.classicStatus != null) InfoRow("Bluetooth", st.classicStatus.toString())
        if (showDiagnostics) {
            if (st.lastStatusHex != null) InfoRow("status (F103)", "0x${st.lastStatusHex}")
            if (st.lastErrorHex != null) InfoRow("error (F102)", "0x${st.lastErrorHex}")
            if (st.lastResponseHex != null) InfoRow("response (F101)", "0x${st.lastResponseHex}")
        }
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.refreshState() }, modifier = Modifier.weight(1f).height(48.dp),
                border = BorderStroke(1.dp, Ub.Outline)) { Text("Refresh", color = Ub.Text) }
            OutlinedButton(onClick = { controller.disconnect() }, modifier = Modifier.weight(1f).height(48.dp),
                border = BorderStroke(1.dp, Ub.Outline)) { Text("Disconnect", color = Ub.Accent) }
        }
    }
    if (renaming) {
        RenameDialog(shownName, "A name for this bar in Unbarred. Clear it to use your phone's Bluetooth name for the bar, or the name the bar broadcasts.",
            onDismiss = { renaming = false },
            onSave = { nick -> controller.renameConnected(nick); renaming = false })
    }
}

/**
 * Speaker test is disabled: on the SL512X, starting it leaves the bar ignoring every later command
 * until it is power-cycled. Shown greyed out with the reason rather than silently removed.
 */
@Composable
private fun SpeakerTestRow() {
    SwitchRow("Speaker test tone",
        "Disabled for now — on tested bars it stops the bar responding until it's power-cycled.",
        checked = false, enabled = false) {}
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

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ScreenPreview() {
    UnbarredTheme {
        Screen(FakeSoundbarController().apply { scanAndConnect() }, onConnect = {})
    }
}
