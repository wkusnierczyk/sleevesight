package app.sleevesight.app.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import app.sleevesight.core.settings.SettingsView

/**
 * Sets the settings screen's view (#1447) once, as the settings file is first read by a version
 * with views: a new install starts in *Basic*; someone updating keeps *All*, the default
 * ([app.sleevesight.core.settings.Settings.settingsView]), so nothing they used disappears. A new
 * install is told by its empty file: every version since #435 marks the file it first reads
 * ([PrunePlayersMigration.DONE]), so one that has run before has at least that in it. Runs before
 * the other migrations, which would mark a new file too, and once per file, marked by [DONE]. A file
 * replaced by an empty one (it could not be parsed) starts in *Basic* again, as its settings are
 * back at their defaults then.
 */
internal object SettingsViewMigration : DataMigration<Preferences> {
    /** Set once the view is set. Not a setting: [SettingsCodec] never reads it. */
    val DONE = booleanPreferencesKey("display.settings_view_set")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[DONE] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (currentData.asMap().isEmpty()) this[SettingsCodec.SETTINGS_VIEW] = SettingsView.BASIC.name
            this[DONE] = true
        }

    override suspend fun cleanUp() = Unit
}
