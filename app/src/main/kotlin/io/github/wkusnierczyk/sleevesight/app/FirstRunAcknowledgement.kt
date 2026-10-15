package io.github.wkusnierczyk.sleevesight.app

import android.content.Context

/**
 * Whether the user has read the first-run screen and continued past it. Granting notification
 * access in Settings before opening the app does not set it, so the screen is always seen before
 * the app goes further.
 *
 * A `SharedPreferences` boolean for now; it moves to DataStore with the other settings (#50).
 */
class FirstRunAcknowledgement(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val acknowledged: Boolean
        get() = prefs.getBoolean(KEY, false)

    fun acknowledge() {
        prefs.edit().putBoolean(KEY, true).apply()
    }

    private companion object {
        const val PREFS = "first_run"
        const val KEY = "acknowledged"
    }
}
