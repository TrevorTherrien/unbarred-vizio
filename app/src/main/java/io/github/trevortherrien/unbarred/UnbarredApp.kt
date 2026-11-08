package io.github.trevortherrien.unbarred

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the [SoundbarController]. The Activity and [BarControlService] share this
 * one instance, so the BLE link can outlive the Activity while the notification controls are up.
 */
class UnbarredApp : Application() {

    val controller: SoundbarController by lazy { BleSoundbarController(this) }

    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)

        // Run the notification-controls service exactly while a bar is ready and the user wants it.
        appScope.launch {
            combine(controller.phase, AppSettings.notificationControls) { phase, enabled -> phase to enabled }
                .collect { (phase, enabled) ->
                    when {
                        enabled && phase == LinkPhase.READY -> BarControlService.start(this@UnbarredApp)
                        !enabled || phase == LinkPhase.IDLE -> BarControlService.stop(this@UnbarredApp)
                    }
                }
        }
    }
}
