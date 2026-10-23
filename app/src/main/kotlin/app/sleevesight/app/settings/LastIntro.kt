package app.sleevesight.app.settings

import android.content.Context
import androidx.core.content.edit
import app.sleevesight.core.settings.Settings

/**
 * *Logo intro at start* (#383) as last read from the settings, mirrored in `SharedPreferences`,
 * which can be read synchronously, as [LastFont] mirrors *Font*: a new process decides whether to
 * play the intro before its first frame, before DataStore's first read of the settings. None
 * stored is the default. Like the settings, it is not backed up.
 */
class LastIntro(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var value: Boolean
        get() = prefs.getBoolean(KEY, DEFAULT)
        set(on) {
            if (on != value) prefs.edit { putBoolean(KEY, on) }
        }

    private companion object {
        const val PREFS = "last_intro"
        const val KEY = "intro"
        val DEFAULT: Boolean = Settings().introAtStart
    }
}
