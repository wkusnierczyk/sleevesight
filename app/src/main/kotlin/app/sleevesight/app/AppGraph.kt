package app.sleevesight.app

import android.content.Context
import android.content.pm.PackageManager
import android.database.SQLException
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.sleevesight.app.history.HistoryRecorder
import app.sleevesight.app.pipeline.AppPipelineParts
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.pipeline.pipelineKeys
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
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.Caches
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.StorePipelineCache
import app.sleevesight.store.db.CacheDatabase
import app.sleevesight.store.history.HistoryDatabase
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** The pipeline's scope, off the main thread; a failure is logged by its type, never thrown. */
    private val pipelineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Log.e(TAG, "Pipeline: ${e::class.simpleName}") },
        )

    val notificationAccess = NotificationAccess(appContext)

    val firstRun = FirstRunAcknowledgement(appContext)

    /** The user's settings. DataStore allows one instance per file in a process, so only here. */
    val settings = SettingsStore(preferences(appContext, SETTINGS_FILE))

    /** The user's keys, sealed with the app's Android Keystore key, in a file of their own. */
    val secrets = SecretStore(preferences(appContext, SECRETS_FILE), SecretCipher(AndroidKeystoreKey()))

    /**
     * The pipeline's network side, on the process's one `NetClient`, which `:wiring` creates and
     * keeps (#132): made once, since the rate limiters belong to the client. It sends nothing of
     * its own; the pipeline, the art fetch and the settings screen's model list send through it.
     */
    private val wiring = appPipelineWiring(BuildConfig.VERSION_NAME, JvmAccentFolder, JvmResourceReader)

    /** The AI services' clients, for the settings screen's model list and the pipeline. */
    val aiProviders: Map<ProviderId, AiProvider> = wiring.aiProviders

    /**
     * The latest settings read, for the caches, which read them on every call. Null before the
     * first read; the pipeline, the caches' only caller, starts after it.
     */
    private val latestSettings = settings.settings.stateIn(mainScope, SharingStarted.Eagerly, null)

    private val normalizer = Normalizer(JvmAccentFolder)

    /**
     * The retrieval, artist and synthesis caches, in a database of their own under the app's
     * `databases/`, which the backup and transfer rules exclude: unlike the history, they can be
     * evicted or rebuilt at the cost of the lookups and paid calls again.
     */
    private val caches =
        Caches(
            AndroidSqliteDriver(CacheDatabase.Schema, appContext, CACHE_DATABASE),
            { checkNotNull(latestSettings.value) { "The caches are used before the settings' first read" } },
            normalizer,
        )

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
            artLoader = ArtLoader(appContext.contentResolver, HttpArt { wiring.art(it) }, Dispatchers.IO),
            scope = mainScope,
            followed = settings.followedPlayers,
        )

    /**
     * The session history and the record of track changes (D15), in a database of their own under
     * the app's `databases/`, which the backup and transfer rules exclude. Cleared together by
     * `SessionHistory.clear`.
     */
    val history = SessionHistory(AndroidSqliteDriver(HistoryDatabase.Schema, appContext, HISTORY_DATABASE))

    /**
     * The pipeline, fed from the session layer: lookups and insights while the app is visible, or
     * also in the background when the user turns that on (D6); nothing before the first run is
     * acknowledged. Runs off the main thread, since the adapters parse their responses where they
     * are called.
     */
    val pipeline =
        PipelineHost(
            acknowledged = firstRun.state,
            nowPlaying = sessions.nowPlaying,
            settings = latestSettings.filterNotNull(),
            keyChanges = secrets.changes,
            readKeys = secrets::pipelineKeys,
            parts = AppPipelineParts(wiring, normalizer, StorePipelineCache(caches, Dispatchers.IO)),
            // The type only: a message could quote a response or a request.
            onFailure = { Log.e(TAG, "The pipeline stopped: ${it::class.simpleName}") },
        )

    init {
        // Each player the session layer sees appears under *Players to follow*, switched off.
        mainScope.launch { SeenPlayersRecorder(sessions.seenPlayers, settings::playerSeen).run() }
        // What the session layer sees is recorded whether or not the app is visible.
        mainScope.launch { HistoryRecorder(sessions, history, Dispatchers.IO).run() }
        pipeline.start(pipelineScope)
        // Expired cache entries are deleted on start; reads never return them either way.
        mainScope.launch {
            latestSettings.filterNotNull().first()
            try {
                withContext(Dispatchers.IO) { caches.purgeExpired() }
            } catch (e: SQLException) {
                Log.w(TAG, "The cache could not be purged: ${e::class.simpleName}")
            }
        }
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
        const val TAG = "Sleevesight"
        const val SETTINGS_FILE = "settings"
        const val SECRETS_FILE = "secrets"
        const val HISTORY_DATABASE = "history.db"
        const val CACHE_DATABASE = "cache.db"

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
