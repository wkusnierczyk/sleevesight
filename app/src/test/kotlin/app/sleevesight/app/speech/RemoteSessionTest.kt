package app.sleevesight.app.speech

import android.media.session.MediaController
import android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
import android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.PlaybackInfoBuilder

/**
 * The remote session a reading holds (#803), and what its callback reports: the first followed,
 * not a call, remote and playing session, with nothing read of a player not followed, nor of a
 * call beyond its mark; each playback state read as the session layer reads it, by whether the
 * session played remotely as last reported (Spotify Connect's `PAUSED` at speed 1 is playing);
 * each audio info with its route and volume; the session's end as no longer remote.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteSessionTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun candidate(
        player: String,
        music: RemoteMusic,
        call: Boolean = false,
        read: MutableList<String> = mutableListOf(),
    ) = SessionCandidate(
        player,
        call = {
            read += "$player call"
            call
        },
        music = {
            read += "$player music"
            music
        },
    )

    private fun remoteMusic(
        remote: Boolean = true,
        playing: Boolean = true,
    ) = FakeRemoteMusic().also {
        it.remote = remote
        it.playing = playing
    }

    @Test
    fun theFirstFollowedRemoteSessionThatPlaysIsHeldAndNothingOfAnotherPlayerIsRead() {
        val read = mutableListOf<String>()
        val chosen = remoteMusic()
        val sessions =
            listOf(
                candidate("other.player", remoteMusic(), read = read),
                candidate(Settings.SPOTIFY, remoteMusic(), call = true, read = read),
                candidate(Settings.SPOTIFY, remoteMusic(remote = false), read = read),
                candidate(Settings.SPOTIFY, remoteMusic(playing = false), read = read),
                candidate(Settings.SPOTIFY, chosen, read = read),
            )
        assertSame(chosen, remoteToHold(sessions) { it == Settings.SPOTIFY })
        assertEquals("not a word of the player not followed", 0, read.count { it.startsWith("other.player") })
        assertEquals("a call's music is not read", 3, read.count { it.endsWith("music") })
    }

    @Test
    fun noneIsHeldWhenNoFollowedSessionPlaysRemotely() {
        val sessions = listOf(candidate(Settings.SPOTIFY, remoteMusic(remote = false)))
        assertNull(remoteToHold(sessions) { true })
        assertNull(remoteToHold(emptyList()) { true })
    }

    private fun controller(remote: Boolean): MediaController {
        val controller = MediaController(app, MediaSession(app, "test").sessionToken)
        shadowOf(controller).setPackageName(Settings.SPOTIFY)
        shadowOf(controller).setPlaybackInfo(info(remote, volume = 0))
        return controller
    }

    private fun info(
        remote: Boolean,
        volume: Int,
    ): MediaController.PlaybackInfo {
        val type = if (remote) PLAYBACK_TYPE_REMOTE else PLAYBACK_TYPE_LOCAL
        return PlaybackInfoBuilder
            .newBuilder()
            .setVolumeType(type)
            .setCurrentVolume(volume)
            .setMaxVolume(100)
            .build()
    }

    private fun state(
        state: Int,
        speed: Float,
    ): PlaybackState = PlaybackState.Builder().setState(state, 0L, speed).build()

    @Test
    fun aRemoteSessionsReportsAreReadAsTheSessionLayerReadsThem() {
        val controller = controller(remote = true)
        val music = ControllerRemoteMusic(controller)
        val reports = mutableListOf<RemoteReport>()
        music.watch { reports += it }

        shadowOf(controller).executeOnPlaybackStateChanged(state(PlaybackState.STATE_PAUSED, 1f))
        shadowOf(controller).executeOnPlaybackStateChanged(state(PlaybackState.STATE_PAUSED, 0f))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Connect's PAUSED at speed 1 plays", listOf(true, false), reports.map { it.playing })

        shadowOf(controller).executeOnSessionDestroyed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, reports.last().remote)
        assertEquals("ended: no longer remote", false, music.remote)
    }

    @Test
    fun anAudioInfoReportsItsRouteAndVolumeAndPlaybackIsThenReadByThatRoute() {
        val reports = mutableListOf<RemoteReport>()
        val callback = ReportingCallback(Settings.SPOTIFY, remote = true, ended = {}) { reports += it }
        callback.onAudioInfoChanged(info(remote = true, volume = 12))
        assertEquals(true, reports.last().remote)
        assertEquals(12, reports.last().volume)

        callback.onAudioInfoChanged(info(remote = false, volume = 30))
        assertEquals(false, reports.last().remote)
        // On the phone, Spotify's PAUSED is paused, whatever its speed.
        callback.onPlaybackStateChanged(state(PlaybackState.STATE_PAUSED, 1f))
        assertEquals(false, reports.last().playing)
    }
}
