package app.sleevesight.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.sleevesight.app.BuildConfig
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The user's settings, kept in DataStore and read into the `:core` [Settings] type (architecture,
 * driver 6), which holds the defaults. Keys are not settings: they are in
 * [SecretStore][app.sleevesight.app.secrets.SecretStore].
 *
 * **A file that cannot be read** (an I/O error) never reads as the defaults, which would follow a
 * player the user switched off, or use a source they turned off: the read is retried, after
 * [FIRST_RETRY] and then twice as long each time up to [MAX_RETRY] ([backoff]), counted from the
 * last read that succeeded, until one succeeds, and the flows go on with each change after it.
 * Meanwhile [settings] emits nothing new, so the screen keeps what it showed, or shows nothing
 * yet. [followedPlayers] emits nothing before its first read succeeds, so the session layer
 * waits, as for any first read; a failure after a read names no player, so the session layer
 * follows none until the retry succeeds. A file DataStore cannot parse is replaced by an empty
 * one (the corruption handler where the store is created): the settings return to their defaults.
 * Losing the settings costs the user their choices, never a key or the cache.
 *
 * **The defaults** are [defaults] wherever the user has not chosen: the app's store has this
 * build's, [BUILD_DEFAULTS]; a test's store has `:core`'s unless it passes others.
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val defaults: Settings = Settings(),
) {
    private val stored: Flow<Settings> = dataStore.data.map { SettingsCodec.read(it, defaults) }

    /** The current settings, and each change to them; nothing while the file cannot be read. */
    val settings: Flow<Settings> = stored.retryReads {}.distinctUntilChanged()

    /**
     * The package names of the players switched on under *Players to follow*, for the session layer
     * (#48), which reads only these players' sessions and drops the others' events. A player is
     * followed only on the user's stored choice: nothing is emitted before the first read
     * succeeds, and a read that fails after it names no player until the retry succeeds.
     */
    val followedPlayers: Flow<Set<String>> =
        flow {
            // Per collection: before its first value, a failure emits nothing, so the session layer
            // waits rather than reading sessions it could not record (`playerSeen` would fail too).
            var read = false
            emitAll(
                stored
                    .map { s -> s.followedPlayers.filterValues { it }.keys }
                    .onEach { read = true }
                    .retryReads { if (read) emit(emptySet()) },
            )
        }.distinctUntilChanged()

    /**
     * Applies [transform] to the stored settings, atomically. Only the settings it changes are
     * written ([SettingsCodec.write]), so what a later version stored survives a downgrade.
     */
    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            SettingsCodec.write(transform(SettingsCodec.read(prefs, defaults)), prefs, defaults)
        }
    }

    /**
     * Records a player the session layer has seen, so *Players to follow* shows a switch for it,
     * off: a new player is followed only once the user switches it on. A player already listed
     * keeps its switch. The session layer names only players that reported music (#435); a phone
     * call's package ([Settings.isCall]) is never listed, whoever names it.
     */
    suspend fun playerSeen(packageName: String) {
        if (Settings.isCall(packageName)) return
        update { s ->
            val players = s.followedPlayers
            if (packageName in players) s else s.copy(followedPlayers = players + (packageName to false))
        }
    }

    companion object {
        /**
         * The settings' defaults in this build: `:core`'s, with developer mode's as the build sets
         * it (`BuildConfig.DEVELOPER_MODE_DEFAULT`, #451): on in a build for Play's testing tracks,
         * so testers reach *Diagnostics* without seven taps on *Version*, and off in any other.
         */
        val BUILD_DEFAULTS: Settings = Settings(developerMode = BuildConfig.DEVELOPER_MODE_DEFAULT)

        /** The first retry of a failed read: soon, as a passing I/O error clears in moments. */
        val FIRST_RETRY: Duration = 250.milliseconds

        /** The longest wait between retries of a read that keeps failing. */
        val MAX_RETRY: Duration = 30.seconds

        /** Doublings of [FIRST_RETRY] past [MAX_RETRY]; more would only grow the number. */
        private const val DOUBLINGS = 7

        /**
         * The wait before retrying a read after [failures] failed reads since the last that
         * succeeded (0 for the first): [FIRST_RETRY], doubled for each, up to [MAX_RETRY].
         */
        internal fun backoff(failures: Int): Duration =
            minOf(FIRST_RETRY * 2.0.pow(failures.coerceIn(0, DOUBLINGS)), MAX_RETRY)

        /**
         * Retries this flow after each I/O error, after [backoff], calling [onFailure] first; any
         * other error ends it. The failures are counted per collection, and from the last value:
         * after a read that succeeds, the next failure is retried after [FIRST_RETRY] again.
         */
        private fun <T> Flow<T>.retryReads(onFailure: suspend FlowCollector<T>.() -> Unit): Flow<T> {
            val reads = this
            return flow {
                var failures = 0
                emitAll(
                    reads
                        .onEach { failures = 0 }
                        .retryWhen { cause, _ ->
                            (cause is IOException).also { io ->
                                if (io) {
                                    onFailure()
                                    delay(backoff(failures++))
                                }
                            }
                        },
                )
            }
        }
    }
}
