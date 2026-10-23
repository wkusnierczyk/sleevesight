package app.sleevesight.app

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.ArtLoader
import app.sleevesight.app.session.HttpArt
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.SystemActiveSessions
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** *Players to follow* and the session layer, through the app graph as the app wires them (#126). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FollowedPlayersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val graph get() = app.graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stores by lazy { TestStores(tmp.root) }

    @Before
    fun acknowledgeFirstRun() = FirstRunAcknowledgement(app).acknowledge()

    @After
    fun tearDown() {
        scope.cancel()
        stores.close()
    }

    private fun storedPlayers(store: SettingsStore = graph.settings): Map<String, Boolean> = store.now().followedPlayers

    /** Sets [player]'s switch under *Players to follow*, as the settings screen does. */
    private fun follow(
        player: String,
        on: Boolean,
        store: SettingsStore = graph.settings,
    ) = runBlocking { store.update { it.copy(followedPlayers = it.followedPlayers + (player to on)) } }

    /** A session of [player], playing "Sundown". */
    private fun addSession(player: String): MediaController {
        val controller = MediaController(app, MediaSession(app, "test").sessionToken)
        shadowOf(controller).setPackageName(player)
        shadowOf(controller).setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Sundown").build(),
        )
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(controller)
        return controller
    }

    /** Every value [sessions] publishes as the track shown, from now on. */
    private fun tracksShown(sessions: SessionRepository): List<NowPlaying?> =
        mutableListOf<NowPlaying?>().also { shown -> scope.launch { sessions.nowPlaying.collect { shown += it } } }

    @Test
    fun aNewlySeenPlayerIsListedSwitchedOffAndNotFollowed() {
        val controller = addSession(OTHER_PLAYER)
        val shown = tracksShown(graph.sessions)
        graph.sessions.start()

        idleUntil("the player listed") { OTHER_PLAYER in storedPlayers() }
        assertEquals(mapOf(Settings.SPOTIFY to true, OTHER_PLAYER to false), storedPlayers())
        assertEquals(setOf(Settings.SPOTIFY), graph.settings.followedPlayers.now())
        idleFor("no track shown, no callback registered") {
            shown.all { it == null } && shadowOf(controller).callbacks.isEmpty()
        }
    }

    @Test
    fun aDefaultPlayerSwitchedOffIsNeverFollowed() {
        follow(Settings.SPOTIFY, on = false)
        val controller = addSession(Settings.SPOTIFY)
        val shown = tracksShown(graph.sessions)
        graph.sessions.start()

        idleUntil("Spotify seen") { Settings.SPOTIFY in graph.sessions.seenPlayers.value }
        idleFor("no track shown, no callback registered") {
            shown.all { it == null } && shadowOf(controller).callbacks.isEmpty()
        }
    }

    @Test
    fun aPlayerSwitchedOnInTheSettingsIsFollowedAndSwitchedOffIsDropped() {
        val controller = addSession(OTHER_PLAYER)
        graph.sessions.start()
        idleUntil("the player listed") { OTHER_PLAYER in storedPlayers() }

        follow(OTHER_PLAYER, on = true)
        idleUntil("its track shown") { graph.sessions.nowPlaying.value != null }
        assertEquals(
            "Sundown",
            graph.sessions.nowPlaying.value
                ?.title,
        )
        assertEquals(1, shadowOf(controller).callbacks.size)

        follow(OTHER_PLAYER, on = false)
        idleUntil("its track gone") { graph.sessions.nowPlaying.value == null }
        assertEquals(0, shadowOf(controller).callbacks.size)
    }

    @Test
    fun aSettingsFileThatCannotBeReadFollowsNothingAndALaterSwitchStillArrives() {
        // As `AppGraph` wires them, over a settings file whose first read fails.
        val flaky = FlakyDataStore(stores.open("settings"))
        val settings = SettingsStore(flaky)
        follow(Settings.SPOTIFY, on = false, store = settings)
        flaky.failures.set(1)
        val sessions =
            SessionRepository(
                sessions = SystemActiveSessions(app),
                acknowledged = MutableStateFlow(true),
                artLoader = ArtLoader(app.contentResolver, HttpArt.NONE, Dispatchers.IO),
                scope = scope,
                followed = settings.followedPlayers,
            )
        val controller = addSession(Settings.SPOTIFY)
        val shown = tracksShown(sessions)
        sessions.start()

        idleUntil("the read retried") { flaky.reads.get() >= 2 }
        idleFor("no track shown, no callback registered") {
            shown.all { it == null } && shadowOf(controller).callbacks.isEmpty()
        }

        follow(Settings.SPOTIFY, on = true, store = settings)
        idleUntil("Spotify followed") { sessions.nowPlaying.value != null }
        assertEquals(1, shadowOf(controller).callbacks.size)
    }

    @Test
    fun aFollowedPlayerIsReleasedWhenAReadFailsAndFollowedAgainAfterTheRetry() {
        // Spotify followed by default; the settings' first read emits, then fails.
        val flaky = FlakyDataStore(stores.open("settings"))
        flaky.failuresAfterAValue.set(1)
        val sessions =
            SessionRepository(
                sessions = SystemActiveSessions(app),
                acknowledged = MutableStateFlow(true),
                artLoader = ArtLoader(app.contentResolver, HttpArt.NONE, Dispatchers.IO),
                scope = scope,
                followed = SettingsStore(flaky).followedPlayers,
            )
        val controller = addSession(Settings.SPOTIFY)
        val shown = tracksShown(sessions)
        sessions.start()

        idleUntil("Spotify followed again") { flaky.reads.get() >= 2 && sessions.nowPlaying.value != null }
        assertEquals(listOf(null, "Sundown", null, "Sundown"), shown.map { it?.title })
        assertEquals(1, shadowOf(controller).callbacks.size)
    }

    private companion object {
        const val OTHER_PLAYER = "com.example.player"
    }
}
