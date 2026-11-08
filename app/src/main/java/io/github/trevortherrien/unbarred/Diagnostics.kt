package io.github.trevortherrien.unbarred

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App preferences, persisted in SharedPreferences. Each flag is a plain [MutableStateFlow] so the
 * UI can bind to it directly; [init] loads the saved values once and then writes every change
 * back. Device state is still never cached — only the user's own choices are stored here.
 *
 * Before [init] runs (e.g. JVM unit tests) the flows simply hold their defaults.
 */
object AppSettings {
    private const val PREFS = "unbarred_prefs"

    /** Master switch: when off, nothing is written to the in-app log or to Logcat. */
    val logging = MutableStateFlow(false)
    /** With [logging] on, also capture the chatty per-write / per-notify lines. */
    val verboseLogging = MutableStateFlow(false)
    /** Subscribe to NOTIFY on connect so the UI tracks external (remote) changes. */
    val liveUpdates = MutableStateFlow(true)
    /** Surface the F101–F103 response/error/status channels in the Device card. */
    val showDiagnostics = MutableStateFlow(false)
    /** While the app is open, the phone's volume keys adjust the bar instead of the phone. */
    val volumeKeys = MutableStateFlow(true)
    /** Keep a notification with quick controls (and the link alive) while connected. */
    val notificationControls = MutableStateFlow(true)
    /** How far one volume-key press / notification tap moves the bar's volume. */
    val volumeStep = MutableStateFlow(2)

    private val booleans: Map<String, MutableStateFlow<Boolean>> = mapOf(
        "logging" to logging,
        "verbose_logging" to verboseLogging,
        "live_updates" to liveUpdates,
        "show_diagnostics" to showDiagnostics,
        "volume_keys" to volumeKeys,
        "notification_controls" to notificationControls,
    )
    private val ints: Map<String, MutableStateFlow<Int>> = mapOf(
        "volume_step" to volumeStep,
    )

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        booleans.forEach { (key, flow) ->
            flow.value = prefs.getBoolean(key, flow.value)
            flow.drop(1).onEach { prefs.edit().putBoolean(key, it).apply() }.launchIn(scope)
        }
        ints.forEach { (key, flow) ->
            flow.value = prefs.getInt(key, flow.value)
            flow.drop(1).onEach { prefs.edit().putInt(key, it).apply() }.launchIn(scope)
        }
    }
}

/**
 * A small ring buffer of log lines mirrored from the controller, so the Settings screen can show
 * and export a diagnostic trace without the user needing adb/Logcat. Everything here — the
 * buffer and the Logcat helpers — is a no-op unless [AppSettings.logging] is on.
 */
object AppLog {
    private const val CAP = 1000
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val on get() = AppSettings.logging.value

    /** Status changes, events, errors (when logging is on). */
    fun add(line: String) {
        if (!on) return
        val stamped = "${fmt.format(Date())}  $line"
        _lines.value = (_lines.value + stamped).let { if (it.size > CAP) it.takeLast(CAP) else it }
    }

    /** Per-write / per-notify detail: needs logging AND verbose logging on. */
    fun verbose(line: String) {
        if (AppSettings.verboseLogging.value) add(line)
    }

    // Logcat, gated by the same master switch.
    fun d(tag: String, msg: String) { if (on) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (on) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (on) Log.w(tag, msg) }

    fun clear() { _lines.value = emptyList() }

    fun exportText(): String = buildString {
        appendLine("Unbarred for VIZIO — diagnostic log")
        appendLine("exported ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("—".repeat(32))
        _lines.value.forEach { appendLine(it) }
    }
}
