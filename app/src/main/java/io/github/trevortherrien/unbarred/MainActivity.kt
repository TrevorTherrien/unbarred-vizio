package io.github.trevortherrien.unbarred

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

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
        // Always-dark UI: light status/nav bar icons regardless of the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            UnbarredTheme {
                var accepted by remember { mutableStateOf(Disclaimer.isAccepted(this)) }
                if (!accepted) {
                    DisclaimerScreen(
                        onAccept = { Disclaimer.setAccepted(this); accepted = true },
                        onDecline = { finishAffinity() }
                    )
                } else {
                    // Double-back on the Sound tab finishes the activity, which disconnects
                    // (SoundbarViewModel.onCleared) and so also removes the notification.
                    Screen(vm.controller, onConnect = ::connectOrRequest, onExit = ::finish)
                }
            }
        }
    }
}

private val VOLUME_KEYS = setOf(
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE
)
