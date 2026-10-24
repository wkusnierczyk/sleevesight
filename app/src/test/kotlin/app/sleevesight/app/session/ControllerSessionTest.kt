package app.sleevesight.app.session

import android.media.AudioAttributes
import android.media.session.MediaController
import android.media.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.PlaybackInfoBuilder

/** [ControllerSession.marksCall]: a call is a session whose audio is for voice communication (#435). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
}
