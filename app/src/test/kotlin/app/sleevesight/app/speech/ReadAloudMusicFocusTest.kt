package app.sleevesight.app.speech

import android.media.AudioManager
import android.media.VolumeProvider
import app.sleevesight.core.settings.ReadAloudMusic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A remote player's session, recorded (#803): what a reading asked of it, and its state, which the
 * test changes as the player or the user would ([report]).
 */
internal class FakeRemoteMusic(
    override var volumeControl: Int = VolumeProvider.VOLUME_CONTROL_FIXED,
    override var volume: Int = 0,
    override var maxVolume: Int = 0,
) : RemoteMusic {
    override var remote = true
    override var playing = true
    val asked = mutableListOf<String>()
    var watching: (() -> Unit)? = null

    override fun pause() {
        asked += "pause"
    }

    override fun play() {
        asked += "play"
    }

    override fun setVolumeTo(volume: Int) {
        asked += "volume $volume"
    }

    override fun adjustVolume(direction: Int) {
        asked += if (direction == AudioManager.ADJUST_LOWER) "lower" else "raise"
    }

    override fun watch(changed: () -> Unit) {
        watching = changed
    }

    override fun unwatch() {
        watching = null
    }

    /** The session reports a change: [change] applied, then told. */
    fun report(change: FakeRemoteMusic.() -> Unit) {
        change()
        watching?.invoke()
    }
}

/**
 * The music while reading aloud (#803): played on the phone, it is held by the audio focus alone,
 * as before; played on another device, through its session as well: paused where its volume is
 * fixed (and for *Pause playback*), and played again after unless the user took it over; lowered
 * and set back where its volume can be set; always given back as the reading ends, and never
 * touched when the focus is refused.
 */
class ReadAloudMusicFocusTest {
    private val audio = FakeFocus()
    private var playing: RemoteMusic? = null
    private val focus = ReadAloudMusicFocus(audio) { playing }

    private fun take(how: ReadAloudMusic) = assertTrue(focus.take(how) {})

    @Test
    fun musicOnThePhoneIsHeldByTheAudioFocusAloneAsBefore() {
        take(ReadAloudMusic.LOWER)
        assertEquals(listOf(ReadAloudMusic.LOWER), audio.taken)
        assertFalse(focus.pausedInstead)
        focus.release()
        assertEquals(1, audio.releases)
    }

    @Test
    fun reduceVolumeOnASpeakerOfFixedVolumePausesItAndPlaysItAgainAfter() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.LOWER)
        assertEquals("the focus too, for the voice", listOf(ReadAloudMusic.LOWER), audio.taken)
        assertEquals(listOf("pause"), speaker.asked)
        assertTrue("said on Home", focus.pausedInstead)

        speaker.report { playing = false }
        focus.release()
        assertEquals(listOf("pause", "play"), speaker.asked)
        assertFalse(focus.pausedInstead)
        assertEquals(1, audio.releases)
    }

    @Test
    fun pausePlaybackOnARemoteSpeakerPausesItThroughItsSessionWhateverItsVolume() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.PAUSE)
        assertEquals(listOf("pause"), speaker.asked)
        assertFalse("paused as chosen: nothing to say", focus.pausedInstead)
        focus.release()
        assertEquals(listOf("pause", "play"), speaker.asked)
    }

    @Test
    fun reduceVolumeOnASpeakerOfAbsoluteVolumeLowersItAndSetsItBackAfter() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.LOWER)
        assertEquals(listOf("volume 12"), speaker.asked)
        assertFalse(focus.pausedInstead)
        speaker.report { volume = 12 }
        focus.release()
        assertEquals(listOf("volume 12", "volume 40"), speaker.asked)
    }

    @Test
    fun aVolumeTheUserSetsDuringTheReadingIsKept() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.LOWER)
        speaker.report { volume = 12 }
        speaker.report { volume = 20 }
        focus.release()
        assertEquals(listOf("volume 12"), speaker.asked)
    }

    @Test
    fun reduceVolumeOnASpeakerOfRelativeVolumeStepsItDownAndAsManyUpAfter() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_RELATIVE, volume = 10, maxVolume = 20)
        playing = speaker
        take(ReadAloudMusic.LOWER)
        assertEquals(List(7) { "lower" }, speaker.asked)
        focus.release()
        assertEquals(List(7) { "lower" } + List(7) { "raise" }, speaker.asked)
    }

    @Test
    fun musicTheUserPlaysAndPausesAgainDuringTheReadingStaysPaused() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.PAUSE)
        speaker.report { playing = false }
        speaker.report { playing = true }
        speaker.report { playing = false }
        focus.release()
        assertEquals(listOf("pause"), speaker.asked)
    }

    @Test
    fun aReportOfPlayingBeforeThePauseLandsIsNotTheUsers() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.PAUSE)
        speaker.report { volume = 0 }
        focus.release()
        assertEquals(listOf("pause", "play"), speaker.asked)
    }

    @Test
    fun musicMovedToThePhoneDuringTheReadingIsNotPlayedAgain() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.PAUSE)
        speaker.report { remote = false }
        focus.release()
        assertEquals(listOf("pause"), speaker.asked)
    }

    @Test
    fun aRefusedFocusLeavesTheSpeakerAlone() {
        val speaker = FakeRemoteMusic().also { playing = it }
        audio.grants = false
        assertFalse(focus.take(ReadAloudMusic.PAUSE) {})
        assertTrue(speaker.asked.isEmpty())
        assertFalse(focus.pausedInstead)
    }

    @Test
    fun theMusicIsGivenBackOnceAndTheSpeakerNoLongerWatched() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.LOWER)
        focus.release()
        focus.release()
        assertEquals(listOf("pause", "play"), speaker.asked)
        assertEquals(null, speaker.watching)
    }

    @Test
    fun takenAnotherWayForTheNextReadingTheSameSpeakerIsHeldAfresh() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.PAUSE)
        // Paused by the reading, so no longer found playing.
        playing = null
        take(ReadAloudMusic.LOWER)
        assertEquals(listOf("pause", "play", "volume 12"), speaker.asked)
    }
}
