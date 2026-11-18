package app.sleevesight.app.session

import android.media.AudioAttributes
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.PlaybackInfoBuilder

/** [ControllerSession.marksCall]: a call is a session whose audio is for voice communication (#435). */
@RunWith(RobolectricTestRunner::class)
class ControllerSessionTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun sessionWithUsage(usage: Int?): ControllerSession {
        val controller = MediaController(app, MediaSession(app, "test").sessionToken)
        if (usage != null) {
            shadowOf(controller).setPlaybackInfo(
                PlaybackInfoBuilder
                    .newBuilder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
                    .build(),
            )
        }
        return ControllerSession(controller)
    }

    @Test
    fun voiceCommunicationMarksACall() {
        assertTrue(sessionWithUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).marksCall)
        assertTrue(sessionWithUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING).marksCall)
    }

    @Test
    fun mediaOrNoPlaybackInfoIsNotACall() {
        assertFalse(sessionWithUsage(AudioAttributes.USAGE_MEDIA).marksCall)
        assertFalse(sessionWithUsage(null).marksCall)
    }

    // Review of #1417: a player that advertises only the combined play/pause action gets the media
    // button, pressed and released; one that advertises Pause itself gets `pause()`.
    @Test
    fun onlyPlayPauseIsSentAsTheMediaButton() {
        for ((actions, pressed) in listOf(
            PlaybackState.ACTION_PLAY_PAUSE to true,
            PlaybackState.ACTION_PAUSE to false,
        )) {
            val controller = MediaController(app, MediaSession(app, "test").sessionToken)
            shadowOf(controller).setPlaybackState(
                PlaybackState
                    .Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(actions)
                    .build(),
            )
            val keys = mutableListOf<KeyEvent>()
            ControllerSession(controller) { keys += it }.send(Transport.PAUSE)
            if (pressed) {
                assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), keys.map { it.action })
                assertTrue(keys.all { it.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE })
            } else {
                assertEquals(emptyList<KeyEvent>(), keys)
            }
        }
        assertFalse(Transport.NEXT.byPlayPauseButton(PlaybackState.ACTION_PLAY_PAUSE))
        assertFalse(Transport.PLAY.byPlayPauseButton(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE))
        assertTrue(Transport.PLAY.byPlayPauseButton(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE))
    }
}
