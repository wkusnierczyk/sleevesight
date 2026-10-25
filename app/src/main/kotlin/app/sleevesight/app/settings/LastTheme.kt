package app.sleevesight.app.settings

import android.content.Context
import androidx.core.content.edit
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice

/**
 * The *Theme* (#444) last read from the settings, mirrored in `SharedPreferences`, which can be read
 * synchronously, as [LastFont] mirrors *Font*: a new process draws its first frames light or dark
 * as chosen, before DataStore's first read of the settings. A name this version does not know, or
 * none, is the default. Like the settings, it is not backed up.
 */
class LastTheme(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var value: ThemeChoice
        get() {
            val name = prefs.getString(KEY, null)
            return ThemeChoice.entries.firstOrNull { it.name == name } ?: DEFAULT
        }
        set(theme) {
            if (theme != value) prefs.edit { putString(KEY, theme.name) }
        }

    private companion object {
        const val PREFS = "last_theme"
        const val KEY = "theme"
        val DEFAULT: ThemeChoice = Settings().theme
    }
}
