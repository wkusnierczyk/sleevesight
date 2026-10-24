package app.sleevesight.app.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * Prunes *Players to follow* once, as the settings file is first read after the upgrade to music
 * players only (#435; [SettingsCodec.prunePlayers]): the players an older version listed only for
 * having had a media session, and every phone call. Run once per file, marked by [DONE], since the
 * players listed after it each reported music and must not be dropped at the next start. A file
 * replaced by an empty one (it could not be parsed) is pruned again, which changes nothing in it
 * but the mark.
 */
internal object PrunePlayersMigration : DataMigration<Preferences> {
    /** Set once the players are pruned. Not a setting: [SettingsCodec] never reads it. */
    val DONE = booleanPreferencesKey("players.pruned")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[DONE] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            SettingsCodec.prunePlayers(this)
            this[DONE] = true
        }

    override suspend fun cleanUp() = Unit
}
