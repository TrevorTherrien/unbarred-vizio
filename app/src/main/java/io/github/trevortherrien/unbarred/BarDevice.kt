package io.github.trevortherrien.unbarred

import android.content.Context

/**
 * A sound bar seen while scanning. [advertisedName] is the BLE name in the advert (available
 * before connecting); the richer A106 "friendly name" only arrives after a connection. When two
 * bars advertise the same name, the user can assign a [nickname] (persisted) to tell them apart.
 */
data class BarDevice(
    val address: String,
    val advertisedName: String?,
    val rssi: Int,
    val nickname: String? = null,
) {
    /** What to show in the picker: the user's nickname, else the advert name, else the address. */
    val label: String get() = nickname?.takeIf { it.isNotBlank() } ?: advertisedName ?: "VIZIO bar"
}

/**
 * Per-bar nicknames keyed by MAC address, in SharedPreferences. Like the disclaimer flag this is
 * a deliberate, tiny bit of persistence (a user label, not volatile device state), so it's exempt
 * from the "don't cache state" rule.
 */
object BarNames {
    private const val PREFS = "vizio_bar_names"

    fun get(context: Context, address: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(address, null)

    /** A blank/null name clears the nickname. */
    fun set(context: Context, address: String, nickname: String?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (nickname.isNullOrBlank()) p.remove(address) else p.putString(address, nickname.trim())
        p.apply()
    }
}
