package app.sleevesight.app

import android.content.Context
import androidx.core.content.edit

/**
 * Whether the user has read the first-run screen and continued past it. Granting notification
 * access in Settings before opening the app does not set it, so the screen is always seen before
 * the app goes further.
 *
 * It gates more than the UI: until it is set, the session layer reads no media session (#48) and
 * the pipeline sends no lookup and no model call (#51), whatever notification access allows.
 *
 * A `SharedPreferences` boolean, unlike the settings in DataStore: the start destination and the
 * gates above read it synchronously, before anything could wait for DataStore's first read.
 */
class FirstRunAcknowledgement(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val acknowledged: Boolean
        get() = prefs.getBoolean(KEY, false)

    fun acknowledge() {
        prefs.edit { putBoolean(KEY, true) }
    }

    private companion object {
        const val PREFS = "first_run"
        const val KEY = "acknowledged"
    }
}
