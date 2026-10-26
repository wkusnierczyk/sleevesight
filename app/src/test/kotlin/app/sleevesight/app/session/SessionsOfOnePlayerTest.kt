package app.sleevesight.app.session

import android.app.PendingIntent
import android.content.Intent
import android.media.session.PlaybackState
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

/**
 * A player with several sessions (#595): Spotify playing to another device (Spotify Connect) may
 * keep a paused session beside the one that plays. The one that plays is shown, wherever the system
 * lists it, and a session of the player that starts playing is shown at once, with no new list.
 */
@RunWith(RobolectricTestRunner::class)
internal class SessionsOfOnePlayerTest : SessionRepositoryFixture() {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun activity(code: Int) =
        PendingIntent.getActivity(context, code, Intent(Intent.ACTION_MAIN), PendingIntent.FLAG_IMMUTABLE)

    // The phone's own session, idle: paused at the start of the last track played here.
    private fun local(screen: PendingIntent? = null) =
        FakeSession(
            SPOTIFY,
            metadata = metadata(title = "Sundown"),
            playbackState = state(PlaybackState.STATE_PAUSED, positionMs = 0, speed = 0f),
            sessionActivity = screen,
        )

    // The session of the remote device, listed after the phone's.
    private fun remote(
        playing: Boolean,
        screen: PendingIntent? = null,
    ) = FakeSession(
        SPOTIFY,
        metadata = metadata(title = "Carefree Highway"),
        playbackState =
            if (playing) {
                state(PlaybackState.STATE_PLAYING, positionMs = 24_000, updatedAt = 1_000)
            } else {
                state(PlaybackState.STATE_PAUSED, positionMs = 24_000, speed = 0f, updatedAt = 1_000)
            },
        sessionActivity = screen,
    )

    @Test
    fun ofTwoSessionsOfOnePlayerThePlayingOneIsShownThoughListedSecond() {
        val phone = activity(1)
        val car = activity(2)
        system.active = listOf(local(phone), remote(playing = true, screen = car))

        repository.start()

        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        val playback = repository.playback.value
        assertEquals(PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, 24.seconds, 1_000, 1f), playback)
        // The bar moves with the track: 10 s after the report, 10 s further on.
        assertEquals(34.seconds, playback.positionAt(11_000))
        assertSame(car, repository.playerActivity())
    }

    @Test
    fun aSessionOfTheSamePlayerThatStartsPlayingIsShownWithNoNewList() {
        val phone = local()
        val car = remote(playing = false)
        system.active = listOf(phone, car)
        repository.start()
        // Neither plays: the first listed.
        assertEquals("Sundown", repository.nowPlaying.value?.title)
        assertEquals(PlaybackStatus.PAUSED, repository.playback.value.status)

        // Played on the other device: the system sends no new list, only the session's state.
        car.settled(state(PlaybackState.STATE_PLAYING, positionMs = 30_000, updatedAt = 2_000))
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        assertEquals(PlaybackStatus.PLAYING, repository.playback.value.status)
        assertEquals(30.seconds, repository.playback.value.position)

        // Paused there: the session shown stays, at its own position, not the phone's idle one.
        car.settled(state(PlaybackState.STATE_PAUSED, positionMs = 41_000, speed = 0f, updatedAt = 13_000))
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        assertEquals(PlaybackInfo(SPOTIFY, PlaybackStatus.PAUSED, 41.seconds, 13_000, 0f), repository.playback.value)

        // Played on the phone again: its session is shown.
        phone.settled(state(PlaybackState.STATE_PLAYING, positionMs = 0, updatedAt = 20_000))
        assertEquals("Sundown", repository.nowPlaying.value?.title)
        assertEquals(PlaybackStatus.PLAYING, repository.playback.value.status)
    }

    @Test
    fun aPausedSessionWhosePositionMovesIsShownOverAnIdleOne() {
        val moving =
            FakeSession(
                SPOTIFY,
                metadata = metadata(title = "Carefree Highway"),
                playbackState = state(PlaybackState.STATE_PAUSED, positionMs = 24_000, speed = 1f, updatedAt = 1_000),
            )
        system.active = listOf(local(), moving)

        repository.start()

        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
    }
}
