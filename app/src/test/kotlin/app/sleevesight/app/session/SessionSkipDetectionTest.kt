package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.TrackChange
import app.sleevesight.core.settings.Crossfade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Skip detection in the session layer (D5): each change judged once as it is published, and the
 * cause carried to the pipeline with its track ([SessionRepository.trackChanges]).
 */
@RunWith(RobolectricTestRunner::class)
internal class SessionSkipDetectionTest : SessionRepositoryFixture() {
    private fun spotify(): FakeSession {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(durationMs = LENGTH_MS))
        system.active = listOf(spotify)
        repository.start()
        return spotify
    }

    /** Moves virtual time on to [ms], running what is due before it. */
    private fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    /** A `PLAYING` state at [ms], Sundown's position then putting its end [leftAtChangeMs] after [CHANGE_MS]. */
    private fun FakeSession.playingAt(
        ms: Long,
        leftAtChangeMs: Long,
    ) {
        at(ms)
        send(state(positionMs = LENGTH_MS - leftAtChangeMs - (CHANGE_MS - ms), updatedAt = ms))
    }

    /** The next track's metadata at [CHANGE_MS], settled. */
    private fun FakeSession.changeTo(title: String) {
        at(CHANGE_MS)
        send(metadata(title = title, durationMs = 200_000))
        settle()
    }

    @Test
    fun aChangeLongBeforeTheEndIsASkipAndItsTrackCarriesIt() {
        val spotify = spotify()
        spotify.playingAt(6_500, leftAtChangeMs = 23_000)
        spotify.changeTo("Carefree Highway")

        val change = checkNotNull(repository.lastChange.value)
        assertEquals(23.seconds, change.timeLeft)
        assertEquals(ChangeCause.SKIP_IN_PLAYER, change.cause)
        val reported = checkNotNull(repository.trackChanges.value)
        assertEquals(repository.nowPlaying.value, reported.nowPlaying)
        assertEquals(ChangeCause.SKIP_IN_PLAYER, reported.cause)

        // The same track filled in later keeps its cause.
        spotify.send(metadata(title = "Carefree Highway", durationMs = 200_000, album = "Sundown"))
        settle()
        assertEquals(
            "Sundown",
            repository.trackChanges.value
                ?.nowPlaying
                ?.album,
        )
        assertEquals(ChangeCause.SKIP_IN_PLAYER, repository.trackChanges.value?.cause)
    }

    @Test
    fun aNaturalEndWithItsLateClusterIsOne() {
        val spotify = spotify()
        for (ms in 7_000L..7_500L step 100) spotify.playingAt(ms, leftAtChangeMs = -50)
        spotify.changeTo("Carefree Highway")

        assertEquals(ChangeCause.NATURAL_END, repository.lastChange.value?.cause)
        assertEquals(ChangeCause.NATURAL_END, repository.trackChanges.value?.cause)
    }

    @Test
    fun theCrossfadeSetAtTheChangeJudgesIt() {
        crossfade = Crossfade.Fixed(12.seconds)
        val spotify = spotify()
        spotify.playingAt(6_500, leftAtChangeMs = 12_000)
        spotify.changeTo("Carefree Highway")

        assertEquals(ChangeCause.NATURAL_END, repository.lastChange.value?.cause)
    }

    @Test
    fun theFirstTrackFoundHasNoChangeIntoItAndEndedOnItsOwn() {
        spotify()
        assertNull(repository.lastChange.value)
        assertEquals(TrackChange(checkNotNull(repository.nowPlaying.value)), repository.trackChanges.value)
    }

    @Test
    fun aTitleChangingUnderOneMediaIdIsNotTheSkipBefore() {
        val stream = FakeSession(SPOTIFY, metadata = metadata(durationMs = LENGTH_MS, mediaId = "a"))
        system.active = listOf(stream)
        repository.start()
        stream.playingAt(6_500, leftAtChangeMs = 23_000)
        at(CHANGE_MS)
        stream.send(metadata(title = "Song 1", mediaId = "radio"))
        settle()
        assertEquals(ChangeCause.SKIP_IN_PLAYER, repository.trackChanges.value?.cause)

        // The stream's next song: the same media id, so no change, and nothing to say it was skipped.
        stream.send(metadata(title = "Song 2", mediaId = "radio"))
        settle()
        assertEquals(
            "Song 2",
            repository.trackChanges.value
                ?.nowPlaying
                ?.title,
        )
        assertEquals(ChangeCause.NATURAL_END, repository.trackChanges.value?.cause)

        // And back to Song 1 on its own: the skip into it before does not come back.
        stream.send(metadata(title = "Song 1", mediaId = "radio", durationMs = 180_000))
        settle()
        assertEquals("Song 1", shown().nowPlaying.title)
        assertEquals(ChangeCause.NATURAL_END, shown().cause)
    }

    @Test
    fun aTrackResumedAfterNoneWasShownDoesNotTakeTheSkipBefore() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = spotify()
        spotify.playingAt(6_500, leftAtChangeMs = 23_000)
        spotify.changeTo("Carefree Highway")
        assertEquals(ChangeCause.SKIP_IN_PLAYER, shown().cause)

        // Spotify closed; another player's track, found with no change into it; then Spotify back
        // on the track it was skipped to, again with no change into it.
        system.change(emptyList())
        settle()
        assertNull(repository.trackChanges.value)
        system.change(listOf(FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song", durationMs = 200_000))))
        settle()
        assertEquals(TrackChange(checkNotNull(repository.nowPlaying.value)), shown())
        system.change(emptyList())
        settle()
        system.change(
            listOf(FakeSession(SPOTIFY, metadata = metadata(title = "Carefree Highway", durationMs = 200_000))),
        )
        settle()

        assertEquals("Carefree Highway", shown().nowPlaying.title)
        assertEquals(ChangeCause.NATURAL_END, shown().cause)
    }

    private fun shown(): TrackChange = checkNotNull(repository.trackChanges.value)

    @Test
    fun aSwitchToAnotherPlayersTrackEndedOnItsOwn() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY, metadata = metadata(durationMs = LENGTH_MS))
        val youTube =
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = metadata(title = "Song", durationMs = 200_000),
                playbackState = state(android.media.session.PlaybackState.STATE_PAUSED, speed = 0f),
            )
        system.active = listOf(spotify, youTube)
        repository.start()
        spotify.playingAt(6_500, leftAtChangeMs = 23_000)
        at(CHANGE_MS)
        spotify.send(state(android.media.session.PlaybackState.STATE_PAUSED, positionMs = 100_000, speed = 0f))
        youTube.send(state(positionMs = 0, updatedAt = CHANGE_MS))
        settle()

        assertEquals(YOUTUBE_MUSIC, repository.nowPlaying.value?.player)
        assertNull(repository.lastChange.value?.timeLeft)
        assertEquals(ChangeCause.NATURAL_END, repository.trackChanges.value?.cause)
    }

    private companion object {
        const val LENGTH_MS = 225_000L
        const val CHANGE_MS = 8_000L
    }
}
