package app.sleevesight.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.sleevesight.app.history.HistoryRecorder
import app.sleevesight.app.history.HistoryRetention
import app.sleevesight.app.secrets.AndroidKeystoreKey
import app.sleevesight.app.secrets.SecretCipher
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.session.ArtLoader
import app.sleevesight.app.session.HttpArt
import app.sleevesight.app.session.NotificationAccess
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.SystemActiveSessions
import app.sleevesight.app.settings.SeenPlayersRecorder
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.history.HistoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's objects, wired by hand in one place (architecture, *Technology choices*): one graph
 * per process, held by [SleevesightApplication]. Components take what they need through their
 * constructors; nothing outside this class constructs a long-lived object.
 */
class AppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** The process's main-thread scope, for components that live as long as it does. */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val notificationAccess = NotificationAccess(appContext)

    val firstRun = FirstRunAcknowledgement(appContext)

    /** The user's settings. DataStore allows one instance per file in a process, so only here. */
    val settings = SettingsStore(preferences(appContext, SETTINGS_FILE))

    /** The user's keys, sealed with the app's Android Keystore key, in a file of their own. */
    val secrets = SecretStore(preferences(appContext, SECRETS_FILE), SecretCipher(AndroidKeystoreKey()))

    /**
     * The AI services' clients, for the settings screen's model list. Empty until the pipeline is
     * wired on the phone (#51), which takes them from `:wiring` (`PipelineWiring.aiProviders`, on the
     * process's one `NetClient`); until then the screen offers each service's default model.
     */
    val aiProviders: Map<ProviderId, AiProvider> = emptyMap()

    /**
     * The session layer, following the players switched on under *Players to follow*: a switch
     * turned on or off reaches it at once. It waits for the settings' first read before it reads
     * any session, so a player switched off is never followed in between, and follows none while
     * the file cannot be read.
     */
    val sessions =
        SessionRepository(
            sessions = SystemActiveSessions(appContext),
            acknowledged = firstRun.state,
            // `http` art waits for the pipeline on the phone (#51): `HttpArt { wiring.art(it) }`.
            artLoader = ArtLoader(appContext.contentResolver, HttpArt.NONE, Dispatchers.IO),
            scope = mainScope,
            followed = settings.followedPlayers,
        )

    /**
     * The session history and the record of track changes (D15), in a database of their own under
     * the app's `databases/`, which the backup and transfer rules exclude. Cleared together by
     * `SessionHistory.clear`.
     */
    val history = SessionHistory(AndroidSqliteDriver(HistoryDatabase.Schema, appContext, HISTORY_DATABASE))

    /** *Keep history for* and *Clear history*. */
    val historyRetention = HistoryRetention(history, settings.settings.map { it.historyLifetime }, Dispatchers.IO)

    init {
        // Each player the session layer sees appears under *Players to follow*, switched off.
        mainScope.launch { SeenPlayersRecorder(sessions.seenPlayers, settings::playerSeen).run() }
        // What the session layer sees is recorded whether or not the app is visible.
        val sessionGap = settings.settings.map { it.sessionGap }
        mainScope.launch { HistoryRecorder(sessions, history, Dispatchers.IO, sessionGap).run() }
        mainScope.launch { historyRetention.run() }
    }

    /** A player's name as the phone knows it, else as the design knows it, else its package. */
    fun playerLabel(packageName: String): String {
        val pm = appContext.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            // Not installed, or not visible to this app (package visibility, Android 11 and later).
            KNOWN_PLAYERS[packageName] ?: packageName
        }
    }

    private companion object {
        const val SETTINGS_FILE = "settings"
        const val SECRETS_FILE = "secrets"
        const val HISTORY_DATABASE = "history.db"

        val KNOWN_PLAYERS = mapOf(Settings.SPOTIFY to "Spotify")

        /**
         * A Preferences DataStore in the app's own files (`files/datastore/`), which the backup and
         * transfer rules exclude with everything else. A file DataStore cannot parse is replaced by
         * an empty one: the settings return to their defaults, the keys read as not set.
         */
        fun preferences(
            context: Context,
            name: String,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile(name) },
            )
    }
}
