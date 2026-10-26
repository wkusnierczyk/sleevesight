package app.sleevesight.app

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.SQLException
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.diagnostics.DiagnosticsLog
import app.sleevesight.app.history.HistoryRecorder
import app.sleevesight.app.history.HistoryRetention
import app.sleevesight.app.pipeline.AppPipelineParts
import app.sleevesight.app.pipeline.ModelCheck
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.pipeline.afterAcknowledged
import app.sleevesight.app.pipeline.online
import app.sleevesight.app.pipeline.pipelineKeys
import app.sleevesight.app.pipeline.rebuilds
import app.sleevesight.app.pipeline.shown
import app.sleevesight.app.secrets.AndroidKeystoreKey
import app.sleevesight.app.secrets.SecretCipher
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.session.ArtLoader
import app.sleevesight.app.session.DataStoreLearningMark
import app.sleevesight.app.session.HttpArt
import app.sleevesight.app.session.NextInQueue
import app.sleevesight.app.session.NotificationAccess
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.SkipDetector
import app.sleevesight.app.session.SkipDetectorLearning
import app.sleevesight.app.session.SystemActiveSessions
import app.sleevesight.app.session.UserActions
import app.sleevesight.app.session.onlyWhen
import app.sleevesight.app.settings.LastFont
import app.sleevesight.app.settings.LastIntro
import app.sleevesight.app.settings.LastTheme
import app.sleevesight.app.settings.PrunePlayersMigration
import app.sleevesight.app.settings.SeenPlayersRecorder
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.ListeningSessionFacts
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.LinkStyle
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.store.ArtistKeeping
import app.sleevesight.store.Caches
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.StoreArtistResults
import app.sleevesight.store.StorePipelineCache
import app.sleevesight.store.db.CacheDatabase
import app.sleevesight.store.history.HistoryDatabase
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's objects, wired by hand in one place (architecture, *Technology choices*): one graph
 * per process, held by [SleevesightApplication]. Components take what they need through their
 * constructors; nothing outside this class constructs a long-lived object.
 *
 * @param settingsData the settings' file, made here unless a test passes a stand-in.
 */
class AppGraph(
    context: Context,
    settingsData: DataStore<Preferences> =
        preferences(context.applicationContext, SETTINGS_FILE, listOf(PrunePlayersMigration)),
    secretCipher: SecretCipher = SecretCipher(AndroidKeystoreKey()),
    musicBrainzThrough: (MusicBrainzLookup) -> MusicBrainzLookup = { it },
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

    /**
     * The user's settings, with this build's defaults, in [settingsData]. DataStore allows one
     * instance per file in a process, so it is made only for the graph.
     */
    val settings = SettingsStore(settingsData, SettingsStore.BUILD_DEFAULTS)

    /** The user's keys, sealed with the app's Android Keystore key, in a file of their own. */
    val secrets = SecretStore(preferences(appContext, SECRETS_FILE), secretCipher)

    /**
     * Where the settings screen saves a setting or a key, or removes keys: the process's scope, so
     * leaving the screen, or a rotation, does not cut a save short.
     */
    val settingsSaves: CoroutineScope get() = mainScope

    /** *Font* as last read, for a new process's first frames. */
    private val lastFont = LastFont(appContext)

    /**
     * *Font* (#377), for the theme: the stored choice, as it changes, held for the process, so a
     * new activity (a rotation, a switch to dark mode, a return to the app) starts in it. Before
     * the settings' first read in a new process, the font last read ([LastFont]). Read whether or
     * not the first run is acknowledged: the first-run screen is drawn in it too, and reading the
     * settings file sends nothing.
     */
    val font: StateFlow<FontChoice> =
        settings.settings
            .map { it.font }
            .distinctUntilChanged()
            .onEach { lastFont.value = it }
            .stateIn(mainScope, SharingStarted.Eagerly, lastFont.value)

    /** *Theme* as last read, for a new process's first frames. */
    private val lastTheme = LastTheme(appContext)

    /**
     * *Theme* (#444), for the theme, as [font] is: the stored choice, as it changes, held for the
     * process, and before the settings' first read in a new process, the theme last read
     * ([LastTheme]). From Android 12 each is also given to the system as the app's night mode
     * ([nightMode]), so the starting window and the splash screen, which come before any of the
     * app's code, are drawn as chosen too; below it they follow the system's dark theme.
     */
    val theme: StateFlow<ThemeChoice> =
        settings.settings
            .map { it.theme }
            .distinctUntilChanged()
            .onEach {
                lastTheme.value = it
                nightMode(it)
            }.stateIn(mainScope, SharingStarted.Eagerly, lastTheme.value)

    /**
     * *Link style* (#519), a developer setting, for the theme: the stored choice, as it changes,
     * held for the process. Before the settings' first read in a new process, the default
     * (underlined): unlike [font] and [theme], it is not kept for a new process's first frames, as
     * a developer option trying a look on the phone need not be; a link on screen then may switch
     * to the highlight as the settings are read.
     */
    val linkStyle: StateFlow<LinkStyle> =
        settings.settings
            .map { it.linkStyle }
            .distinctUntilChanged()
            .stateIn(mainScope, SharingStarted.Eagerly, Settings().linkStyle)

    /**
     * Gives [theme] to the system as the app's night mode (Android 12 and later), which it keeps
     * across restarts and applies to the app's configuration: *System* follows the system's dark
     * theme (`MODE_NIGHT_AUTO`, as AndroidX's own night mode maps it), *Light* and *Dark* are fixed.
     * Unchanged, it changes nothing; changed, the system restarts the open activity in it.
     */
    private fun nightMode(theme: ThemeChoice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode =
            when (theme) {
                ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
            }
        appContext.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
    }

    /** *Logo intro at start* as last read, for a new process's decision before its first frame. */
    private val lastIntro = LastIntro(appContext)

    /**
     * *Logo intro at start* (#383): the stored choice, held for the process, and before the
     * settings' first read in a new process, the one last read ([LastIntro]), which is what a cold
     * start goes by, since it decides before its first frame.
     */
    val introAtStart: StateFlow<Boolean> =
        settings.settings
            .map { it.introAtStart }
            .distinctUntilChanged()
            .onEach { lastIntro.value = it }
            .stateIn(mainScope, SharingStarted.Eagerly, lastIntro.value)

    /**
     * *Keep what sources said about each artist* (#420), as last read (off until the first read),
     * for the artist-level cache and the retrieval cache alike.
     */
    private val artistKeeping =
        ArtistKeeping.over({ caches }, Dispatchers.IO) { latestSettings.value?.keepArtistResults == true }

    /**
     * *Clear history*'s boundary (#547): each run and preparation is marked with it as it starts,
     * and what it finds is kept (in the caches, MusicBrainz's answers in memory, the session's
     * facts) only if no clear came since ([forgetTracks]).
     */
    private val clearEpoch = ClearEpoch()

    /**
     * What the sources give for an artist, in the caches' artist-level table (#238), only while
     * [artistKeeping] is on. It opens the database only at its first query, a lookup's, after the
     * first-run acknowledgement. What was kept, there and in the retrieval cache, is deleted whenever
     * the switch reads off (`init`), at start too, so an install upgraded from a version that kept it
     * unasked keeps nothing.
     */
    private val artistResults = StoreArtistResults({ caches }, Dispatchers.IO, artistKeeping, clearEpoch)

    /**
     * The pipeline's network side, on the process's one `NetClient`, which `:wiring` creates and
     * keeps (#132): made once, since the rate limiters belong to the client. It sends nothing of
     * its own; the pipeline, the art fetch and the settings screen's model list send through it.
     * What the sources give for an artist goes in the caches' artist-level table ([artistResults]).
     */
    internal val wiring =
        appPipelineWiring(
            BuildConfig.VERSION_NAME,
            JvmAccentFolder,
            JvmResourceReader,
            artistResults,
            // *Show genres* (#444) as last read: on until the first read, which comes before any lookup.
            genres = { latestSettings.value?.showGenres != false },
            epoch = clearEpoch,
            musicBrainzThrough = musicBrainzThrough,
        )

    /**
     * Ends the graph for an emulated process's end only ([SleevesightApplication.onTerminate], which
     * Robolectric calls after each test): cancels the process's scopes, so this graph's coroutines
     * stop at their next cancellable suspension rather than run on into the next test (blocking
     * work in flight runs to its end, and may end with an error from the closed client), then
     * closes the pipeline's network client without waiting for the coroutines to stop. On a phone
     * the graph lives as long as the process.
     */
    internal fun close() {
        pipelineScope.cancel()
        mainScope.cancel()
        wiring.close()
    }

    /** The AI services' clients, for the settings screen's model list and the pipeline. */
    val aiProviders: Map<ProviderId, AiProvider> = wiring.aiProviders

    /**
     * Whether each AI service's selected model can be used (#540): checked against the service's
     * model list at start (once lookups are allowed) and at each opening of the model choice, and
     * marked unavailable by a call the service answers that it does not know the model or refuses
     * it. While the switched-on service's model is unavailable, no insight is written, and the
     * settings and Home say so.
     */
    val modelCheck =
        ModelCheck(
            aiProviders,
            key = { provider -> SecretId.forProvider(provider)?.let { secrets.get(it) } },
            scope = pipelineScope,
            stored = { provider -> SecretId.forProvider(provider)?.let { secrets.isStored(it) } == true },
        )

    /**
     * The latest settings read, for the caches, which read them on every call, the pipeline and
     * the history's recorder and retention, and the home screen and its pages (#447), which come
     * back to them laid out as they were left. Nothing is read before the first run is
     * acknowledged; null until the first read after it, so also for a while on each cold start
     * (launch, or after process death), while the stored settings are read: the home screen draws
     * neither *Sources* nor the genres until then. The pipeline, the caches' only caller,
     * starts after it, and the history's retention deletes nothing (and opens no `history.db`)
     * before it.
     */
    val latestSettings: StateFlow<Settings?> =
        settings.settings.afterAcknowledged(firstRun.state).stateIn(mainScope, SharingStarted.Eagerly, null)

    /**
     * The switched-on service and its selected model, while that model is found unavailable
     * ([modelCheck], #540), for Home and the pipeline's rebuilds; null otherwise.
     */
    val unavailableModel: StateFlow<UnavailableModel?> =
        modelCheck.shown(latestSettings).stateIn(mainScope, SharingStarted.Eagerly, null)

    private val normalizer = Normalizer(JvmAccentFolder)

    /**
     * Skip detection (D5), with the user's *Crossfade* as last read. The session layer waits for it
     * to relearn from the recorded track changes, and for the settings' first read.
     */
    val skipDetector = SkipDetector { latestSettings.value?.crossfade ?: Crossfade.Autodetect }

    /** When the user last acted in Sleevesight, for the track changes' features (D15). */
    private val userActions = UserActions()

    /**
     * The retrieval, artist, artist-level and synthesis caches, in a database of their own under the app's
     * `databases/`, which the backup and transfer rules exclude: unlike the history, they can be
     * evicted or rebuilt at the cost of the lookups and paid calls again; so one a newer version
     * left is rebuilt rather than failing ([RebuiltOnDowngrade]).
     */
    internal val caches by lazy {
        Caches(
            AndroidSqliteDriver(
                CacheDatabase.Schema,
                appContext,
                CACHE_DATABASE,
                callback = RebuiltOnDowngrade(CacheDatabase.Schema),
            ),
            { checkNotNull(latestSettings.value) { "The caches are used before the settings' first read" } },
            normalizer,
        )
    }

    /**
     * The session layer, following the players switched on under *Players*: a switch
     * turned on or off reaches it at once. It waits for the settings' first read before it reads
     * any session, so a player switched off is never followed in between, and follows none while
     * the file cannot be read.
     */
    val sessions: SessionRepository =
        SessionRepository(
            sessions = SystemActiveSessions(appContext),
            // And skip detection has relearned from the recorded changes (`skipDetector`), with
            // the settings read, so no change is judged under Autodetect when *Crossfade* is fixed.
            acknowledged =
                combine(firstRun.state, skipDetector.restored, latestSettings) { ack, restored, read ->
                    ack && restored && read != null
                },
            // `http` art is a request like the pipeline's: only while lookups are allowed (D6).
            artLoader =
                ArtLoader(
                    appContext.contentResolver,
                    HttpArt { wiring.art(it) }.onlyWhen { pipeline.lookupsAllowed },
                    Dispatchers.IO,
                ),
            scope = mainScope,
            followed = settings.followedPlayers,
            classify = skipDetector::classify,
        )

    /**
     * The session history and the record of track changes (D15), in a database of their own under
     * the app's `databases/`, which the backup and transfer rules exclude. Cleared together by
     * `SessionHistory.clear`.
     */
    val history = SessionHistory(AndroidSqliteDriver(HistoryDatabase.Schema, appContext, HISTORY_DATABASE))

    /** The one writer of [history]: what the session layer sees, and the deletions below. */
    private val historyRecorder =
        HistoryRecorder(
            sessions,
            history,
            Dispatchers.IO,
            latestSettings.filterNotNull().map { it.sessionGap },
            lastAction = userActions::lastAt,
            onCleared = skipDetector::reset,
        )

    /**
     * What skip detection has learned, relearned at each start from the changes recorded since its
     * last reset, which the diagnostics screen offers; the mark of that reset in a file of its own.
     */
    val skipLearning =
        SkipDetectorLearning(
            skipDetector,
            history,
            DataStoreLearningMark.open { appContext.preferencesDataStoreFile(SKIP_DETECTION_FILE) },
            Dispatchers.IO,
        )

    /**
     * *Keep history for* and *Clear history*, through the recorder, in the process's scope. *Clear
     * history* also deletes everything else kept about the tracks played ([forgetTracks]).
     */
    val historyRetention =
        HistoryRetention(
            historyRecorder,
            latestSettings.filterNotNull().map { it.historyLifetime },
            mainScope,
            forget = ::forgetTracks,
        )

    /**
     * The artist facts the listening session's insights cited, which the next tracks' prompts leave
     * out (#239): kept in memory, per the history's latest session, which the recorder writes as a
     * track starts, usually before its insight is asked for; a track whose insight comes first counts
     * in the session before it.
     */
    internal val sessionFacts =
        ListeningSessionFacts(clearEpoch) { withContext(Dispatchers.IO) { history.latestSessionNumber() } }

    /**
     * The pipeline, fed from the session layer: lookups and insights while the app is visible, or
     * also in the background when the user turns that on (D6); nothing before the first run is
     * acknowledged. Runs off the main thread, since the adapters parse their responses where they
     * are called. One per process, like this graph: its coordinator's first action starting unset
     * is what makes a cold start wait for the user's first action (D5, #190), so it is never
     * recreated or persisted.
     */
    val pipeline: PipelineHost =
        PipelineHost(
            acknowledged = firstRun.state,
            changes = sessions.trackChanges,
            latestPlaying = { sessions.nowPlaying.value },
            settings = latestSettings.filterNotNull(),
            // A rebuild too when the switched-on service's model is found unavailable, or no longer
            // is (#540): the parts then leave out, or bring back, the AI access.
            keyChanges = merge(secrets.changes, modelCheck.rebuilds(latestSettings)),
            readKeys = secrets::pipelineKeys,
            // The caches only once a pipeline is built, after the first-run acknowledgement; the
            // database opens at its first query.
            parts =
                AppPipelineParts(
                    wiring,
                    normalizer,
                    sessionFacts,
                    modelCheck,
                ) { StorePipelineCache(caches, Dispatchers.IO, artistKeeping, clearEpoch) },
            normalizer = normalizer,
            // The type only: a message could quote a response or a request.
            onFailure = { Log.e(TAG, "The pipeline stopped: ${it::class.simpleName}") },
            onAction = userActions::acted,
            // The next item in the shown player's queue, prepared ahead (D13).
            next = combine(sessions.nowPlaying, sessions.queue, NextInQueue::of),
            // Back online, the playing track's unreachable sources are asked again (#192).
            online = appContext.getSystemService(ConnectivityManager::class.java).online(),
            epoch = clearEpoch,
        )

    /**
     * The diagnostics screen's: the pipeline's events kept in memory from the start of the process,
     * the track changes and the caches' sizes, read when the screen asks, and what skip detection
     * has learned, with its reset ([skipLearning]).
     */
    val diagnostics =
        Diagnostics(
            log = DiagnosticsLog(),
            readChanges = history::changes,
            readCacheStats = { caches.stats() },
            io = Dispatchers.IO,
            // An export outlives the screen that started it.
            scope = mainScope,
            version = BuildConfig.VERSION_NAME,
            skipLearning = skipLearning,
        )

    init {
        // The events, in memory only, off the main thread.
        pipelineScope.launch { diagnostics.log.follow(pipeline.events) }
        // Each player the session layer sees appears under *Players*, switched off.
        mainScope.launch { SeenPlayersRecorder(sessions.seenPlayers, settings::playerSeen).run() }
        // What the session layer sees is recorded whether or not the app is visible.
        mainScope.launch { historyRecorder.run() }
        mainScope.launch { historyRetention.run() }
        // `history.db` opens only after the first run is acknowledged.
        mainScope.launch {
            firstRun.state.first { it }
            skipLearning.restore()
        }
        pipeline.start(pipelineScope)
        // The selected model checked at start, and when the service, its model or a key changes (#540).
        pipelineScope.launch {
            modelCheck.run(latestSettings.filterNotNull(), secrets.changes, pipeline.lookupsAllowed)
        }
        // What was kept about artists is deleted whenever *Keep what sources said about each
        // artist* reads off (#420): when it is turned off, and on start while it is off. The switch
        // reads off before the clear starts, so a lookup in progress keeps nothing after it.
        mainScope.launch {
            latestSettings
                .filterNotNull()
                .map { it.keepArtistResults }
                .distinctUntilChanged()
                .collect { keeps ->
                    // One that fails is tried again at the next lookup while the switch is off.
                    if (!keeps && !artistKeeping.clear()) Log.w(TAG, "The artist cache could not be cleared")
                }
        }
        // Expired cache entries are deleted on start, once the first run is acknowledged (the
        // settings are read only then); reads never return them either way.
        mainScope.launch {
            latestSettings.filterNotNull().first()
            try {
                withContext(Dispatchers.IO) { caches.purgeExpired() }
            } catch (e: SQLException) {
                Log.w(TAG, "The cache could not be purged: ${e::class.simpleName}")
            }
        }
    }

    /**
     * The rest of *Clear history* (#547), after the history. First the boundary ([clearEpoch]):
     * once the writes in progress end, nothing found by a run or preparation started before it is
     * kept. Then the pipeline's part ([PipelineHost.forget]): its run and preparation cancelled, the
     * insight shown and the coordinator's ledger gone, and the track playing not looked up again
     * until another one plays. Then every cache (the sources' findings, the artist resolutions and
     * results, the insights and cards written), in one transaction and in turn with what is written
     * about artists ([ArtistKeeping.clearWith]); MusicBrainz's answers held in memory; the artist
     * facts the listening session cited; the session layer's last change, which names the track
     * before; and the diagnostics' steps, which name the tracks. The API keys and the settings stay.
     * Returns whether the caches were cleared; the rest cannot fail.
     */
    private suspend fun forgetTracks(): Boolean {
        clearEpoch.advance()
        pipeline.forget()
        // Again just before the caches' clear, so it waits for the writes under way then too.
        clearEpoch.advance()
        val cleared = artistKeeping.clearWith { withContext(Dispatchers.IO) { caches.clearAll() } }
        if (!cleared) Log.w(TAG, "The caches could not be cleared")
        wiring.forgetRemembered()
        sessionFacts.forget()
        sessions.forgetLastChange()
        diagnostics.log.clear()
        return cleared
    }

    /**
     * A player's name as the phone knows it, else as the design knows it, else its package. A
     * player with a launcher icon (#435) or a media browser service (#535) is visible to the app
     * through the manifest's `<queries>`.
     */
    fun playerLabel(packageName: String): String {
        val pm = appContext.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            // Not installed, or not visible to this app (package visibility, Android 11 and later):
            // a player with neither a launcher icon nor a media browser service.
            KNOWN_PLAYERS[packageName] ?: packageName
        }
    }

    /**
     * The intent that opens a player from its launcher entry, for *Open the app ↗* under its name in
     * the settings (#444); null for one with none, or not visible to this app.
     */
    fun playerLaunchIntent(packageName: String): Intent? =
        appContext.packageManager.getLaunchIntentForPackage(packageName)

    /**
     * A player's own launcher icon, as the phone has it (#535; an adaptive icon is drawn under the
     * phone's mask), for its row under *Players*: nothing bundled or fetched. Null for a player not
     * installed, or not visible to this app (package visibility: [playerVisible]), whose row, if
     * shown, has no icon. A package-manager call: not for the main thread.
     */
    fun playerIcon(packageName: String): Drawable? =
        try {
            appContext.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /**
     * Whether the phone shows this app [packageName] (#535): installed, and visible through the
     * manifest's `<queries>` (a launcher entry, a media browser service, or a media-button
     * receiver). Not seeing one is no proof it is uninstalled, so nothing is deleted on it:
     * *Players* only hides it ([SettingsStore.forgetPlayer] deletes on Android's word).
     */
    fun playerVisible(packageName: String): Boolean =
        try {
            appContext.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private companion object {
        const val TAG = "Sleevesight"
        const val SETTINGS_FILE = "settings"
        const val SECRETS_FILE = "secrets"
        const val SKIP_DETECTION_FILE = "skip_detection"
        const val HISTORY_DATABASE = "history.db"
        const val CACHE_DATABASE = "cache.db"

        val KNOWN_PLAYERS = mapOf(Settings.SPOTIFY to "Spotify")

        /**
         * A Preferences DataStore in the app's own files (`files/datastore/`), which the backup and
         * transfer rules exclude with everything else. A file DataStore cannot parse is replaced by
         * an empty one: the settings return to their defaults, the keys read as not set.
         * [migrations] run before the first read, as DataStore runs them.
         */
        fun preferences(
            context: Context,
            name: String,
            migrations: List<DataMigration<Preferences>> = emptyList(),
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = migrations,
                produceFile = { context.preferencesDataStoreFile(name) },
            )
    }
}
