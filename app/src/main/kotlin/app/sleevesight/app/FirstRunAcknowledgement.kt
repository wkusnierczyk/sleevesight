package app.sleevesight.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has read the first-run screen and continued past it. Granting notification
 * access in Settings before opening the app does not set it, so the screen is always seen before
 * the app goes further.
 *
 * It gates more than the UI: until it is set, the session layer reads no media session (#48) and
 * the pipeline sends no lookup and no model call (#51), whatever notification access allows. They
 * follow [state], which changes when the user continues.
 *
 * A `SharedPreferences` boolean, unlike the settings in DataStore: the start destination and the
 * gates above read it synchronously, before anything could wait for DataStore's first read.
 */
class FirstRunAcknowledgement(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(prefs.getBoolean(KEY, false))

    // Held here: the preferences keep their listeners only weakly.
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> flow.value = prefs.getBoolean(KEY, false) }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val acknowledged: Boolean
        get() = prefs.getBoolean(KEY, false)

    /** [acknowledged], as it changes. */
    val state: StateFlow<Boolean> = flow.asStateFlow()

    fun acknowledge() {
        prefs.edit { putBoolean(KEY, true) }
        flow.value = true
    }

    private companion object {
        const val PREFS = "first_run"
        const val KEY = "acknowledged"
    }
}
