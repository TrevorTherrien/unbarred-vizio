package io.github.trevortherrien.unbarred

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that shows quick controls (volume −/+, EQ cycle, disconnect) in a
 * notification while a bar is connected and the app is open or in the background. It goes away
 * on disconnect (any cause) and when the app is closed (backed out of, or swiped from Recents).
 * Started/stopped by [UnbarredApp] based on [LinkPhase] and [AppSettings.notificationControls].
 */
class BarControlService : Service() {

    companion object {
        private const val CHANNEL_ID = "bar_controls"
        private const val NOTIFICATION_ID = 1
        private const val PKG = "io.github.trevortherrien.unbarred"
        const val ACTION_VOL_DOWN = "$PKG.action.VOL_DOWN"
        const val ACTION_VOL_UP = "$PKG.action.VOL_UP"
        const val ACTION_EQ_NEXT = "$PKG.action.EQ_NEXT"
        const val ACTION_DISCONNECT = "$PKG.action.DISCONNECT"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, BarControlService::class.java))
            }.onFailure {
                // e.g. ForegroundServiceStartNotAllowedException if a connect finished in the background.
                AppLog.add("Notification controls unavailable: ${it.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BarControlService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: SoundbarController get() = (application as UnbarredApp).controller
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    /** What the notification shows; rebuilt only when one of these changes. */
    private data class View(val name: String?, val volume: Int?, val muted: Boolean, val eq: String?)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(currentView()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
        } catch (e: Exception) {
            // Android 14+ refuses a connectedDevice service without the Bluetooth permission.
            AppLog.add("Notification controls couldn't start: ${e.message}")
            stopSelf()
            return
        }

        scope.launch {
            combine(controller.phase, controller.controlValues, controller.deviceName) { phase, _, _ ->
                phase to currentView()
            }.distinctUntilChanged().collect { (phase, view) ->
                if (phase == LinkPhase.IDLE) {
                    stopSelf()
                } else {
                    notificationManager.notify(NOTIFICATION_ID, build(view))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller
        val step = AppSettings.volumeStep.value
        when (intent?.action) {
            ACTION_VOL_DOWN -> c.nudgeVolume(-step)
            ACTION_VOL_UP -> c.nudgeVolume(step)
            ACTION_EQ_NEXT -> if (c.phase.value == LinkPhase.READY) c.cycleEq()
            ACTION_DISCONNECT -> c.disconnect()
        }
        return START_NOT_STICKY
    }

    /** Swiped away from Recents: treat as closing the app — drop the link and the notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.disconnect()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun currentView(): View {
        val v = controller.controlValues.value
        return View(
            name = controller.deviceName.value,
            volume = v[Proto.CHAR_VOLUME],
            muted = v[Proto.CHAR_MUTE] == 1,
            eq = eqLabel(v[Proto.CHAR_VZ_EQ]),
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sound bar controls", NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = "Quick volume and EQ controls while a sound bar is connected"
                setShowBadge(false)
            }
        notificationManager.createNotificationChannel(channel)
    }

    private fun build(view: View): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val volumeText = when {
            view.volume == null -> "Volume —"
            view.muted -> "Muted (${view.volume})"
            else -> "Volume ${view.volume}"
        }
        val text = listOfNotNull(volumeText, view.eq?.let { "EQ $it" }).joinToString("  ·  ")

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_soundbar)
            .setContentTitle(view.name ?: "Sound bar")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .addAction(action(R.drawable.ic_volume_down, "Volume down", ACTION_VOL_DOWN))
            .addAction(action(R.drawable.ic_volume_up, "Volume up", ACTION_VOL_UP))
            .addAction(action(R.drawable.ic_equalizer, "Next EQ (${view.eq ?: "?"})", ACTION_EQ_NEXT))
            .addAction(action(R.drawable.ic_close, "Disconnect", ACTION_DISCONNECT))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, BarControlService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }
}
