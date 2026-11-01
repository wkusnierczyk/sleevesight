package app.sleevesight.app.speech

import android.media.VolumeProvider
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.home.SpokenWords
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Readings of music on a remote speaker (#803), through [ReadAloud] and [ReadAloudMusicFocus]: a
 * reading that replaces another goes on under the hold the first took, its way fixed, whatever the
 * setting says by then, so the music is neither given back nor asked again between them; and Home's
 * note follows the music while the reading goes on.
 */
class ReadAloudRemoteTest {
    private val speech = FakeSpeech()
    private var speaker = FakeRemoteMusic()
    private val focus = ReadAloudMusicFocus(FakeFocus()) { speaker }
    private val settings = MutableStateFlow<Settings?>(Settings())
    private val readAloud =
        ReadAloud(
            speech,
            focus,
            settings,
            SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
            { false },
            { _, _ -> },
            CoroutineScope(Dispatchers.Unconfined),
        ) { Locale.UK }

    private val first = written("Sundown was recorded in 1973 [MB1].")
    private val second = written("It was a hit in Canada [MB1].")

    private fun choose(music: ReadAloudMusic) {
        settings.value = Settings(readAloudMusic = music)
    }

    @Test
    fun aReadingThatReplacesAPauseBeforeItLandsKeepsTheHoldAndPlaysTheMusicAgainAfter() {
        choose(ReadAloudMusic.PAUSE)
        readAloud.readInsight(track(insight = first), first)
        choose(ReadAloudMusic.LOWER)
        readAloud.readInsight(track(insight = second), second)
        assertEquals("asked once", listOf("pause"), speaker.asked)

        speaker.reportPlaying(false)
        speech.finish()
        assertEquals(listOf("pause", "play"), speaker.asked)
    }

    @Test
    fun aReadingThatReplacesALoweredOneNeverPlaysMusicTheUserPaused() {
        speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        choose(ReadAloudMusic.LOWER)
        readAloud.readInsight(track(insight = first), first)
        speaker.reportVolume(12)
        speaker.reportPlaying(false)
        choose(ReadAloudMusic.PAUSE)
        readAloud.readInsight(track(insight = second), second)
        speech.finish()
        assertEquals(listOf("volume 12", "volume 40"), speaker.asked)
    }

    @Test
    fun theNoteGoesWhenTheUserPlaysTheMusicAgainWhileTheInsightIsRead() {
        readAloud.readInsight(track(insight = first), first)
        assertTrue(readAloud.pausedInstead.value)
        speaker.reportPlaying(false)
        speaker.reportPlaying(true)
        assertNotNull("still reading", readAloud.reading.value)
        assertFalse(readAloud.pausedInstead.value)
        speech.finish()
        assertEquals("the user's: not played again", listOf("pause"), speaker.asked)
    }
}
