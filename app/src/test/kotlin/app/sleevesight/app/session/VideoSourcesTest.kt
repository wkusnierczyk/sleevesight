package app.sleevesight.app.session

import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which players are video sources (#1257): the free YouTube app, and no music player. */
class VideoSourcesTest {
    @Test
    fun `the free YouTube app is a video source`() {
        assertTrue(VideoSources.isVideo("com.google.android.youtube"))
    }

    @Test
    fun `YouTube Music and the other music players are not`() {
        assertFalse(VideoSources.isVideo("com.google.android.apps.youtube.music"))
        assertFalse(VideoSources.isVideo(Settings.SPOTIFY))
    }

    @Test
    fun `no player is not a video source`() {
        assertFalse(VideoSources.isVideo(null))
    }
}
