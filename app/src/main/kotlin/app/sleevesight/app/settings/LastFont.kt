package app.sleevesight.app.settings

import android.content.Context
import androidx.core.content.edit
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Settings

/**
 * The *Font* (#377) last read from the settings, mirrored in `SharedPreferences`, which can be read
 * synchronously: a new process draws its first frames in it, before DataStore's first read of the
 * settings, which hold the choice. A name this version does not know, or none, is the default.
 * Like the settings, it is not backed up (`data_extraction_rules.xml` excludes every file).
 */
class LastFont(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var value: FontChoice
        get() = prefs.getString(KEY, null).let { name -> FontChoice.entries.firstOrNull { it.name == name } } ?: DEFAULT
        set(font) {
            if (font != value) prefs.edit { putString(KEY, font.name) }
        }

    private companion object {
        const val PREFS = "last_font"
        const val KEY = "font"
        val DEFAULT: FontChoice = Settings().font
    }
}
