package app.sleevesight.app.session

import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FirstRunAcknowledgement
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.idleFor
import app.sleevesight.app.idleUntil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The listener service and the system's sessions, through the app graph as the app wires them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ListenerServiceTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val manager = app.getSystemService(MediaSessionManager::class.java)

    /** A session of a player not followed, so attaching reads only the list of sessions. */
    private fun addSession(player: String = OTHER_PLAYER): MediaController {
        val controller = MediaController(app, MediaSession(app, "test").sessionToken)
        shadowOf(controller).setPackageName(player)
        shadowOf(manager).addController(controller)
        return controller
    }

    private fun connectListener() {
        Robolectric
            .buildService(SleevesightListenerService::class.java)
            .create()
            .get()
            .onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aBoundListenerReadsNoSessionBeforeTheFirstRunIsAcknowledged() {
        // Notification access granted in Settings before the app was first opened.
        addSession()
        connectListener()

        val sessions = app.graph.sessions
        // Once the settings' first read is in, which the session layer also waits for.
        runBlocking {
            app.graph.settings.followedPlayers
                .first()
        }
        idleFor("no session read before the first run") {
            sessions.seenPlayers.value.isEmpty() && sessions.nowPlaying.value == null
        }

        FirstRunAcknowledgement(app).acknowledge()
        idleUntil("the session list read") { sessions.seenPlayers.value.isNotEmpty() }

        assertEquals(setOf(OTHER_PLAYER), sessions.seenPlayers.value)
    }

    @Test
    fun theSystemsSessionsAndTheirChangesAreRead() {
        addSession()
        val system = SystemActiveSessions(app)
        assertEquals(listOf(OTHER_PLAYER), system.current().map { it.player })

        var heard = emptyList<String>()
        system.listen { list -> heard = list.map { it.player } }
        addSession("com.example.second")
        assertEquals(listOf(OTHER_PLAYER, "com.example.second"), heard)

        system.stop()
        addSession("com.example.third")
        assertEquals(listOf(OTHER_PLAYER, "com.example.second"), heard)
    }

    private companion object {
        const val OTHER_PLAYER = "com.example.player"
    }
}
