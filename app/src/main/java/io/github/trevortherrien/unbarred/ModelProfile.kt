package io.github.trevortherrien.unbarred

import java.util.UUID

/**
 * Per-model limits and quirks that the device does NOT expose over BLE.
 *
 * Numeric ranges (e.g. bass ±10) are shipped in the vendor app's config keyed by
 * model + firmware — the bar doesn't report them over GATT — so they live here.
 * Feature *presence* (does a characteristic exist) IS discoverable at runtime, so
 * that's detected dynamically and combined with [unsupported] below.
 *
 * To add your model: append a [ModelProfile] to [ModelProfiles.PROFILES]. PRs welcome.
 */
data class ModelProfile(
    /** Matched case-insensitively as a substring of the model name (characteristic A101). */
    val id: String,
    val displayName: String,
    /** Range for the signed level trims (bass/treble/center/dialog/sub/height/surround). */
    val levelRange: IntRange = -10..10,
    /** ClearDialog (C106) is unsigned, unlike the other level trims. */
    val dialogRange: IntRange = 0..10,
    /** 2 → render Power-LED as on/off; >2 → a 0..(n-1) slider. */
    val powerLedStates: Int = 2,
    val avDelayMax: Int = 8,
    val ecoPowerStates: Int = 4,
    /** Characteristics to hide even if the firmware happens to expose them. */
    val unsupported: Set<UUID> = emptySet(),
)

object ModelProfiles {
    val DEFAULT = ModelProfile(id = "default", displayName = "Generic VIZIO bar")

    // ---------------- Add your model here (PRs welcome) ----------------
    val PROFILES: List<ModelProfile> = listOf(
        ModelProfile(
            id = "SL512X",
            displayName = "VIZIO Elevate SE (SL512X)",
            levelRange = -10..10,
            dialogRange = 0..10,
            powerLedStates = 2,
            avDelayMax = 8,
            unsupported = setOf(Proto.CHAR_WIDE_SURR_LVL, Proto.CHAR_WIDE_SURR_BAL),
        ),
    )

    fun forModel(modelName: String?): ModelProfile =
        PROFILES.firstOrNull { p -> modelName?.contains(p.id, ignoreCase = true) == true } ?: DEFAULT
}

/**
 * What the connected bar supports: its [profile] (ranges/quirks) plus the set of
 * characteristics actually discovered on it. [availableChars] empty = not yet known
 * (show everything with defaults).
 */
data class Capabilities(
    val profile: ModelProfile = ModelProfiles.DEFAULT,
    val availableChars: Set<UUID> = emptySet(),
    val modelName: String? = null,
) {
    fun supports(char: UUID): Boolean {
        if (char in profile.unsupported) return false
        if (availableChars.isEmpty()) return true
        return char in availableChars
    }
}
