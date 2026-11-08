package io.github.trevortherrien.unbarred

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logging by AppSettings.logging.state()
    val verbose by AppSettings.verboseLogging.state()
    val live by AppSettings.liveUpdates.state()
    val diagnostics by AppSettings.showDiagnostics.state()
    val volumeKeys by AppSettings.volumeKeys.state()
    val notifControls by AppSettings.notificationControls.state()
    val volumeStep by AppSettings.volumeStep.state()
    val logLines by AppLog.lines.state()

    Column(
        Modifier
            .fillMaxSize()
            .background(Ub.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundIconButton(R.drawable.ic_ui_back, "Back", onBack, size = 44)
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel("Controls", Modifier.padding(start = 6.dp, top = 8.dp))
            Panel {
                SwitchRow("Volume keys control the bar",
                    "While Unbarred is open, the phone's volume buttons change the sound bar.", volumeKeys) {
                    AppSettings.volumeKeys.value = it
                }
                SwitchRow("Notification controls",
                    "Volume and EQ buttons in the notification shade while connected. " +
                        "Closes when you disconnect or close the app.", notifControls) {
                    AppSettings.notificationControls.value = it
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("Volume step", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("$volumeStep", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = volumeStep.toFloat(),
                    onValueChange = { AppSettings.volumeStep.value = it.toInt() },
                    valueRange = 1f..5f, steps = 3,
                    colors = SliderDefaults.colors(inactiveTrackColor = Ub.Track),
                )
            }

            SectionLabel("Experimental", Modifier.padding(start = 6.dp, top = 8.dp))
            Panel {
                SwitchRow("Live updates",
                    "Track changes made with the remote. Applies on next connect.", live) {
                    AppSettings.liveUpdates.value = it
                }
                SwitchRow("Show diagnostic channels",
                    "Show the F101–F103 response/error/status values on the System tab.", diagnostics) {
                    AppSettings.showDiagnostics.value = it
                }
            }

            SectionLabel("Diagnostic log", Modifier.padding(start = 6.dp, top = 8.dp)) {
                Text("${logLines.size} lines", fontSize = 12.sp, color = Ub.Muted)
            }
            Panel {
                SwitchRow("Logging",
                    "Keep a diagnostic log in the app and in Android's system log. Off by default.", logging) {
                    AppSettings.logging.value = it
                }
                SwitchRow("Verbose logging",
                    "Also capture every write and notification. Needs Logging on.", verbose && logging,
                    enabled = logging) {
                    AppSettings.verboseLogging.value = it
                }
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Unbarred log")
                                putExtra(Intent.EXTRA_TEXT, AppLog.exportText())
                            }
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Share log", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(onClick = { AppLog.clear() }, modifier = Modifier.height(48.dp),
                        border = BorderStroke(1.dp, Ub.Outline)) { Text("Clear", color = Ub.Text) }
                }
                Surface(color = Ub.Background, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                        if (logLines.isEmpty()) {
                            Text(if (logging) "No log yet — connect to the bar." else "Logging is off.",
                                fontSize = 13.sp, color = Ub.Muted)
                        } else {
                            logLines.takeLast(200).forEach {
                                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ub.Muted)
                            }
                        }
                    }
                }
            }

            SectionLabel("Updates", Modifier.padding(start = 6.dp, top = 8.dp))
            UpdatesPanel()

            SectionLabel("About", Modifier.padding(start = 6.dp, top = 8.dp))
            Panel {
                Text(Disclaimer.TITLE, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp))
                DisclaimerBody()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UpdatesPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Panel {
        InfoRow("Installed version", version)
        Button(
            enabled = !checking,
            onClick = {
                checking = true; result = null
                scope.launch { result = UpdateChecker.check(version); checking = false }
            },
            modifier = Modifier.padding(vertical = 6.dp).height(48.dp)
        ) { Text(if (checking) "Checking…" else "Check for updates", fontWeight = FontWeight.SemiBold) }

        when (val r = result) {
            null -> {}
            is UpdateChecker.Result.UpToDate -> Text("You're on the latest version.", color = Ub.Accent)
            is UpdateChecker.Result.NotConfigured -> Text(r.detail, fontSize = 13.sp, color = Ub.Muted)
            is UpdateChecker.Result.Error -> Text("Couldn't check: ${r.message}", color = Ub.Error)
            is UpdateChecker.Result.Available -> {
                Text("Update available: ${r.version}", fontWeight = FontWeight.SemiBold, color = Ub.Accent)
                if (r.notes.isNotBlank()) Text(r.notes.take(600), fontSize = 13.sp, color = Ub.Muted)
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
