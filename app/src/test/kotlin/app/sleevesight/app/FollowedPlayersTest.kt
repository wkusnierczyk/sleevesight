package app.sleevesight.app

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** *Players to follow* and the session layer, through the app graph as the app wires them (#126). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FollowedPlayersTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val graph get() = app.graph

    private fun storedPlayers(): Map<String, Boolean> =
        runBlocking {
            graph.settings.settings
                .first()
                .followedPlayers
        }

    /** Sets [OTHER_PLAYER]'s switch under *Players to follow*, as the settings screen does. */
    private fun follow(on: Boolean) =
        runBlocking { graph.settings.update { it.copy(followedPlayers = it.followedPlayers + (OTHER_PLAYER to on)) } }

    /** A session of [OTHER_PLAYER], not followed by default, playing "Sundown". */
    private fun addSession(): MediaController {
        val controller = MediaController(app, MediaSession(app, "test").sessionToken)
        shadowOf(controller).setPackageName(OTHER_PLAYER)
        shadowOf(controller).setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Sundown").build(),
        )
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(controller)
        return controller
    }

    @Before
    fun acknowledgeFirstRun() = FirstRunAcknowledgement(app).acknowledge()

    @Test
    fun aNewlySeenPlayerIsListedSwitchedOffAndNotFollowed() {
        val controller = addSession()
        graph.sessions.start()

        idleUntil { OTHER_PLAYER in storedPlayers() }
        assertEquals(mapOf(Settings.SPOTIFY to true, OTHER_PLAYER to false), storedPlayers())
        assertEquals(setOf(Settings.SPOTIFY), runBlocking { graph.settings.followedPlayers.first() })
        assertNull(graph.sessions.nowPlaying.value)
        assertEquals(0, shadowOf(controller).callbacks.size)
    }

    @Test
    fun aPlayerSwitchedOnInTheSettingsIsFollowedAndSwitchedOffIsDropped() {
        val controller = addSession()
        graph.sessions.start()
        idleUntil { OTHER_PLAYER in storedPlayers() }

        follow(on = true)
        idleUntil { graph.sessions.nowPlaying.value != null }
        assertEquals(
            "Sundown",
            graph.sessions.nowPlaying.value
                ?.title,
        )
        assertEquals(1, shadowOf(controller).callbacks.size)

        follow(on = false)
        idleUntil { graph.sessions.nowPlaying.value == null }
        assertEquals(0, shadowOf(controller).callbacks.size)
    }

    private companion object {
        const val OTHER_PLAYER = "com.example.player"
    }
}
