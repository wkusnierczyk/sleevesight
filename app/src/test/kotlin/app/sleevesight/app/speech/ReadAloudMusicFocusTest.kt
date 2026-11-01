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
 * test changes as the player or the user would, each change reported as it is made ([reportPlaying],
 * [reportVolume], [reportRemote]) or queued and delivered later ([queue], [deliver]), as a
 * callback's report may arrive after the session has changed again. With [answersAtOnce], a pause
 * is reported to a watcher while it is asked, before the call returns.
 */
internal class FakeRemoteMusic(
    override var volumeControl: Int = VolumeProvider.VOLUME_CONTROL_FIXED,
    override var volume: Int = 0,
    override var maxVolume: Int = 0,
) : RemoteMusic {
    override var remote = true
    override var playing = true
    var answersAtOnce = false
    val asked = mutableListOf<String>()
    var watching: ((RemoteReport) -> Unit)? = null
    private val queued = mutableListOf<RemoteReport>()

    override fun pause() {
        asked += "pause"
        if (answersAtOnce) reportPlaying(false)
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

    override fun watch(changed: (RemoteReport) -> Unit) {
        watching = changed
    }

    override fun unwatch() {
        watching = null
    }

    fun reportPlaying(now: Boolean) {
        playing = now
        watching?.invoke(RemoteReport(playing = now))
    }

    fun reportVolume(now: Int) {
        volume = now
        watching?.invoke(RemoteReport(remote = remote, volume = now))
    }

    fun reportRemote(now: Boolean) {
        remote = now
        watching?.invoke(RemoteReport(remote = now, volume = volume))
    }

    /** The session becomes [now]'s playing, its report kept for [deliver]. */
    fun queue(now: Boolean) {
        playing = now
        queued += RemoteReport(playing = now)
    }

    /** The reports kept, delivered in order, after every change they report was made. */
    fun deliver() {
        val all = queued.toList()
        queued.clear()
        all.forEach { watching?.invoke(it) }
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
        assertFalse(focus.pausedInstead.value)
        focus.release()
        assertEquals(1, audio.releases)
    }

    @Test
    fun reduceVolumeOnASpeakerOfFixedVolumePausesItAndPlaysItAgainAfter() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.LOWER)
        assertEquals("the focus too, for the voice", listOf(ReadAloudMusic.LOWER), audio.taken)
        assertEquals(listOf("pause"), speaker.asked)
        assertTrue("said on Home", focus.pausedInstead.value)

        speaker.reportPlaying(false)
        focus.release()
        assertEquals(listOf("pause", "play"), speaker.asked)
        assertFalse(focus.pausedInstead.value)
        assertEquals(1, audio.releases)
    }

    @Test
    fun pausePlaybackOnARemoteSpeakerPausesItThroughItsSessionWhateverItsVolume() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.PAUSE)
        assertEquals(listOf("pause"), speaker.asked)
        assertFalse("paused as chosen: nothing to say", focus.pausedInstead.value)
        focus.release()
        assertEquals(listOf("pause", "play"), speaker.asked)
    }

    @Test
    fun reduceVolumeOnASpeakerOfAbsoluteVolumeLowersItAndSetsItBackAfter() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.LOWER)
        assertEquals(listOf("volume 12"), speaker.asked)
        assertFalse(focus.pausedInstead.value)
        speaker.reportVolume(12)
        focus.release()
        assertEquals(listOf("volume 12", "volume 40"), speaker.asked)
    }

    @Test
    fun aVolumeTheUserSetsDuringTheReadingIsKept() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.LOWER)
        speaker.reportVolume(12)
        speaker.reportVolume(20)
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
        speaker.reportPlaying(false)
        speaker.reportPlaying(true)
        speaker.reportPlaying(false)
        focus.release()
        assertEquals(listOf("pause"), speaker.asked)
    }

    @Test
    fun aReportOfPlayingBeforeThePauseLandsIsNotTheUsers() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.PAUSE)
        // Still playing: the pause has not landed.
        speaker.reportPlaying(true)
        focus.release()
        assertEquals(listOf("pause", "play"), speaker.asked)
    }

    @Test
    fun musicMovedToThePhoneDuringTheReadingIsNotPlayedAgain() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.PAUSE)
        speaker.reportRemote(false)
        focus.release()
        assertEquals(listOf("pause"), speaker.asked)
    }

    @Test
    fun reportsDeliveredAfterTheSessionChangedAgainAreReadAsTheyWereGiven() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.PAUSE)
        // The pause lands, the user plays and pauses again, all before a callback runs.
        speaker.queue(false)
        speaker.queue(true)
        speaker.queue(false)
        speaker.deliver()
        focus.release()
        assertEquals(listOf("pause"), speaker.asked)
    }

    @Test
    fun aPauseAcknowledgedAtOnceIsSeenSoTheUsersPlayAndPauseAfterIsKept() {
        val speaker = FakeRemoteMusic().also { playing = it }
        speaker.answersAtOnce = true
        take(ReadAloudMusic.PAUSE)
        speaker.reportPlaying(true)
        speaker.reportPlaying(false)
        focus.release()
        assertEquals(listOf("pause"), speaker.asked)
    }

    @Test
    fun musicNoLongerPlayingOnceWatchedIsLeftAlone() {
        val speaker = FakeRemoteMusic().also { playing = it }
        speaker.playing = false
        take(ReadAloudMusic.LOWER)
        assertFalse(focus.pausedInstead.value)
        focus.release()
        assertTrue(speaker.asked.isEmpty())
    }

    @Test
    fun theMusicIsNoLongerSaidToBePausedOnceTheUserPlaysItAgain() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.LOWER)
        speaker.reportPlaying(false)
        assertTrue(focus.pausedInstead.value)
        speaker.reportPlaying(true)
        assertFalse(focus.pausedInstead.value)
    }

    @Test
    fun theMusicIsNoLongerSaidToBePausedOnceItMovesToThePhone() {
        val speaker = FakeRemoteMusic().also { playing = it }
        take(ReadAloudMusic.LOWER)
        speaker.reportRemote(false)
        assertFalse(focus.pausedInstead.value)
    }

    @Test
    fun aVolumeHoldSetsTheVolumeBackAndNeverPlaysMusicTheUserPaused() {
        val speaker = FakeRemoteMusic(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, volume = 40, maxVolume = 100)
        playing = speaker
        take(ReadAloudMusic.LOWER)
        speaker.reportVolume(12)
        speaker.reportPlaying(false)
        focus.release()
        assertEquals(listOf("volume 12", "volume 40"), speaker.asked)
    }

    @Test
    fun aRefusedFocusLeavesTheSpeakerAlone() {
        val speaker = FakeRemoteMusic().also { playing = it }
        audio.grants = false
        assertFalse(focus.take(ReadAloudMusic.PAUSE) {})
        assertTrue(speaker.asked.isEmpty())
        assertFalse(focus.pausedInstead.value)
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
}
