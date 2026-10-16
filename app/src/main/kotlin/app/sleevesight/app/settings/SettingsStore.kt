package app.sleevesight.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The user's settings, kept in DataStore and read into the `:core` [Settings] type (architecture,
 * driver 6), which holds the defaults. Keys are not settings: they are in
 * [SecretStore][app.sleevesight.app.secrets.SecretStore].
 *
 * A file that cannot be read reads as the defaults: an I/O error for that read, and a file
 * DataStore cannot parse is replaced by an empty one (the corruption handler where the store is
 * created). Losing the settings costs the user their choices, never a key or the cache.
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The current settings, and each change to them. */
    val settings: Flow<Settings> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map(SettingsCodec::read)
            .distinctUntilChanged()

    /**
     * The package names of the players switched on under *Players to follow*, for the session layer
     * (#48), which reads only these players' sessions and drops the others' events.
     */
    val followedPlayers: Flow<Set<String>> =
        settings.map { s -> s.followedPlayers.filterValues { it }.keys }.distinctUntilChanged()

    /** Applies [transform] to the stored settings, atomically. */
    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs -> SettingsCodec.write(transform(SettingsCodec.read(prefs)), prefs) }
    }

    /**
     * Records a player the session layer has seen, so *Players to follow* shows a switch for it,
     * off: a new player is followed only once the user switches it on. A player already listed
     * keeps its switch.
     */
    suspend fun playerSeen(packageName: String) {
        update { s ->
            val players = s.followedPlayers
            if (packageName in players) s else s.copy(followedPlayers = players + (packageName to false))
        }
    }
}
