package app.sleevesight.app.session

import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How a session's reports settle into tracks and track changes (`design/architecture.md`, *Session layer*). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class SessionSettlingTest : SessionRepositoryFixture() {
    /**
     * YouTube Music stopped in the background (no item), then moved on: its new state (item 5)
     * comes just before its new metadata.
     */
    private fun aChangeAfterAStop(queue: Boolean) {
        val youtube = youtubeMusic(queue)
        val tracks = recordTracks()

        youtube.settled(state(PlaybackState.STATE_STOPPED, queueItem = -1))
        youtube.send(state(queueItem = 5))
        youtube.settled(carefree)

        assertEquals(1, changes(tracks))
        assertEquals(listOf(4L, 5L), tracks.map { it?.queueItemId })
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
    }

    @Test
    fun aChangeAfterAStopIsOneChange() = aChangeAfterAStop(queue = true)

    @Test
    fun aChangeAfterAStopIsOneChangeWithoutAQueue() = aChangeAfterAStop(queue = false)

    @Test
    fun theNewTracksStateBeforeItsMetadataIsOneChangeWithNoTransient() {
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.send(state(queueItem = 5))
        scheduler.advanceTimeBy(SessionRepository.SETTLE / 2)
        youtube.send(carefree)
        settle()

        assertEquals(1, changes(tracks))
        assertEquals(
            listOf(
                sundown.copy(player = YOUTUBE_MUSIC, queueItemId = 4),
                sundown.copy(title = "Carefree Highway", player = YOUTUBE_MUSIC, queueItemId = 5),
            ),
            tracks,
        )
    }

    @Test
    fun theNewTracksMetadataBeforeItsStateIsOneChange() {
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.send(carefree)
        youtube.send(state(queueItem = 5))
        settle()

        assertEquals(1, changes(tracks))
        assertEquals(listOf(4L, 5L), tracks.map { it?.queueItemId })
    }

    @Test
    fun aStopAndResumeChangesNoTrack() {
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.settled(state(PlaybackState.STATE_STOPPED, queueItem = -1))
        youtube.settled(metadata())
        youtube.settled(state(queueItem = -1))
        youtube.settled(state(queueItem = 4))

        assertEquals(1, tracks.size)
        assertEquals(PlaybackStatus.PLAYING, repository.playback.value.status)
    }

    @Test
    fun aSongQueuedTwiceIsOneChange() {
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.settled(state(queueItem = 5))
        youtube.settled(metadata())

        assertEquals(1, changes(tracks))
        assertEquals(listOf(4L, 5L), tracks.map { it?.queueItemId })
    }

    @Test
    fun decoratedQueueTitlesStillTellCopiesApart() {
        // The queue names the copies "Sundown (Remastered)"; the metadata says "Sundown".
        val youtube = youtubeMusic(metadata = carefree, item = 3)
        youtube.queue =
            listOf(
                queueItem(3, "Carefree Highway"),
                queueItem(4, "Sundown (Remastered)"),
                queueItem(5, "Sundown (Remastered)"),
            )
        val tracks = recordTracks()

        youtube.send(state(queueItem = 4))
        youtube.settled(metadata())
        youtube.send(state(queueItem = 5))
        youtube.settled(metadata())

        assertEquals(2, changes(tracks))
        assertEquals(listOf(3L, 4L, 5L), tracks.map { it?.queueItemId })
    }

    @Test
    fun youtubeMusicsStartupFillingInTheArtistIsNoChange() {
        // Recorded at the start of a session: the title first, then its artist and album, item 1 throughout.
        val youtube = youtubeMusic(metadata = metadata(title = "Last King standing", artist = null), item = 1)
        val tracks = recordTracks()

        youtube.settled(metadata(title = "Last King standing", artist = "We Are Jazz", album = "Last King standing"))

        assertEquals(0, changes(tracks))
        assertEquals("We Are Jazz", repository.nowPlaying.value?.artists)
        assertEquals(1L, repository.nowPlaying.value?.queueItemId)
    }

    @Test
    fun theArtistFilledInWithoutAQueueItemIsNoChange() {
        val youtube = youtubeMusic(metadata = metadata(artist = null), item = -1)
        val tracks = recordTracks()

        youtube.settled(metadata())

        assertEquals(2, tracks.size)
        assertEquals(0, changes(tracks))
    }

    @Test
    fun metadataSentTwiceBeforeTheStateMovesIsOneChange() {
        // A player that sends the new metadata first, twice, while its state still names the old item.
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.settled(carefree)
        youtube.settled(carefree)
        youtube.settled(state(queueItem = 5))
        youtube.settled(metadata(title = "Carefree Highway", album = "Sundown"))

        assertEquals(1, changes(tracks))
        assertEquals("Carefree Highway", tracks[1]?.title)
        assertEquals(5L, repository.nowPlaying.value?.queueItemId)
    }

    @Test
    fun aSessionAttachedBeforeItsFirstMetadataGivesTheTrackItsItem() {
        val youtube = youtubeMusic(metadata = null, item = 5)
        assertNull(repository.nowPlaying.value)

        youtube.settled(carefree)
        assertEquals(5L, repository.nowPlaying.value?.queueItemId)
    }

    @Test
    fun playbackIsPublishedAtOnceAndTheTrackOnlyOnceSettled() {
        val youtube = youtubeMusic()

        youtube.send(state(PlaybackState.STATE_PAUSED, positionMs = 1_000, speed = 0f, queueItem = 5))
        youtube.send(carefree)
        assertEquals(PlaybackStatus.PAUSED, repository.playback.value.status)
        assertEquals("Sundown", repository.nowPlaying.value?.title)

        scheduler.advanceTimeBy(SessionRepository.SETTLE + 1.milliseconds)
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
    }

    @Test
    fun aPlayerShownBecauseItStartedANewTrackIsShownOnceSettled() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY, playbackState = state(PlaybackState.STATE_PAUSED))
        val youtube =
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = metadata(title = "OldYT"),
                playbackState = state(PlaybackState.STATE_PAUSED, queueItem = 4),
            )
        system.active = listOf(spotify, youtube)
        repository.start()
        val tracks = recordTracks()

        youtube.send(state(queueItem = 5))
        assertEquals(YOUTUBE_MUSIC, repository.playback.value.player)
        youtube.send(metadata(title = "NewYT"))
        settle()

        assertEquals(listOf("Sundown", "NewYT"), tracks.map { it?.title })
        assertEquals(5L, repository.nowPlaying.value?.queueItemId)
    }

    @Test
    fun aPlayerReportingEvery250MsStillSettlesWithinTheLongestWait() {
        val youtube = youtubeMusic()
        youtube.send(carefree)
        repeat(4) {
            scheduler.advanceTimeBy(250.milliseconds)
            youtube.send(state(positionMs = it * 250L, queueItem = 5))
        }
        // 1 s after the burst began, with a report 250 ms ago and more to come.
        scheduler.advanceTimeBy(1.milliseconds)
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        assertEquals(5L, repository.nowPlaying.value?.queueItemId)
    }

    @Test
    fun metadataWellBeforeTheStateIsOneChangeAtTheMetadata() {
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.send(carefree)
        scheduler.advanceTimeBy(500.milliseconds)
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        youtube.settled(state(queueItem = 5))

        assertEquals(1, changes(tracks))
        assertEquals(listOf(4L, null, 5L), tracks.map { it?.queueItemId })
        assertEquals(listOf("Sundown", "Carefree Highway", "Carefree Highway"), tracks.map { it?.title })
    }

    @Test
    fun titlesChangingUnderOneQueueItemAreChanges() {
        // A stream that publishes one item while the songs change.
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.settled(carefree)
        youtube.settled(state(positionMs = 1_000, queueItem = 4))
        youtube.settled(metadata(title = "Rainy Day People"))

        assertEquals(2, changes(tracks))
    }

    @Test
    fun aChangeKeepsWhenItBeganAndThePlaybackBeforeIt() {
        val youtube = youtubeMusic()
        youtube.settled(state(positionMs = 200_000, updatedAt = 7, queueItem = 4))
        assertNull(repository.lastChange.value)

        val began = scheduler.currentTime
        youtube.send(state(positionMs = 0, queueItem = 5))
        scheduler.advanceTimeBy(100.milliseconds)
        youtube.send(carefree)
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals("Carefree Highway", change.track.title)
        assertEquals(began, change.changedAt)
        assertEquals(PlaybackInfo(YOUTUBE_MUSIC, PlaybackStatus.PLAYING, 200.seconds, 7, 1f), change.before)
        assertTrue(scheduler.currentTime > began)
    }

    @Test
    fun aSessionAttachedBetweenTheNewStateAndItsMetadataSeesTheChange() {
        // Attached after the new track's state (item 5) and before its metadata.
        val youtube = youtubeMusic(item = 5)
        val tracks = recordTracks()

        scheduler.advanceTimeBy(SessionRepository.SETTLE / 2)
        youtube.settled(carefree)

        assertEquals(1, changes(tracks))
        assertEquals(listOf("Sundown", "Carefree Highway"), tracks.map { it?.title })
        assertEquals(
            "Carefree Highway",
            repository.lastChange.value
                ?.track
                ?.title,
        )
    }

    @Test
    fun theLongestWaitDoesNotCutANewTracksStateFromItsMetadata() {
        // Reports less than 300 ms apart for a second (a seek bar scrubbed), then the new track's
        // state just before the longest wait runs out, and its metadata just after.
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.send(state(positionMs = 100_000, queueItem = 4))
        for (at in listOf(250L, 500L, 750L)) {
            scheduler.advanceTimeBy(250.milliseconds)
            youtube.send(state(positionMs = 100_000 + at, updatedAt = at, queueItem = 4))
        }
        scheduler.advanceTimeBy(240.milliseconds)
        youtube.send(state(positionMs = 0, updatedAt = 990, queueItem = 5))
        scheduler.advanceTimeBy(20.milliseconds)
        youtube.send(carefree)
        settle()

        assertEquals(
            listOf(
                sundown.copy(player = YOUTUBE_MUSIC, queueItemId = 4),
                sundown.copy(title = "Carefree Highway", player = YOUTUBE_MUSIC, queueItemId = 5),
            ),
            tracks,
        )
        val change = checkNotNull(repository.lastChange.value)
        assertEquals(990L, change.changedAt)
        assertEquals(PlaybackInfo(YOUTUBE_MUSIC, PlaybackStatus.PLAYING, 100_750.milliseconds, 750, 1f), change.before)
    }

    @Test
    fun theLongestWaitDoesNotCutTheSecondOfTwoChangesInOneBurst() {
        // *Next* pressed, reports less than 300 ms apart, and *next* again as the longest wait runs out.
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.send(state(queueItem = 5))
        scheduler.advanceTimeBy(20.milliseconds)
        youtube.send(carefree)
        scheduler.advanceTimeBy(230.milliseconds)
        for (at in listOf(250L, 500L, 750L)) {
            youtube.send(state(positionMs = at, updatedAt = at, queueItem = 5))
            scheduler.advanceTimeBy(if (at == 750L) 240.milliseconds else 250.milliseconds)
        }
        youtube.send(state(queueItem = 6))
        scheduler.advanceTimeBy(20.milliseconds)
        youtube.send(metadata(title = "Rainy Day People"))
        settle()

        assertEquals(listOf(4L, 6L), tracks.map { it?.queueItemId })
        assertEquals(listOf("Sundown", "Rainy Day People"), tracks.map { it?.title })
        assertEquals(0L, repository.lastChange.value?.changedAt)
    }

    @Test
    fun aStopDoesNotReleaseThePreviousTracksItem() {
        // Metadata well before the state, and a stop (no item) before the state moves on.
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.settled(carefree)
        youtube.settled(state(PlaybackState.STATE_STOPPED, queueItem = -1))
        youtube.settled(state(queueItem = 4))
        youtube.settled(state(queueItem = 5))

        assertEquals(1, changes(tracks))
        assertEquals(5L, repository.nowPlaying.value?.queueItemId)
    }

    @Test
    fun aSwitchToAPlayerSettledEarlierIsTimedAtTheSwitch() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY)
        val youtube =
            FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "OldYT"), playbackState = state(queueItem = 4))
        system.active = listOf(spotify, youtube)
        repository.start()
        val recorded = mutableListOf<SessionChange?>()
        scope.launch { repository.lastChange.collect { recorded += it } }

        // YouTube Music changes track while Spotify is shown, then Spotify does.
        scheduler.advanceTimeBy(10.seconds)
        youtube.send(state(queueItem = 5))
        youtube.settled(metadata(title = "NewYT"))
        scheduler.advanceTimeBy(60.seconds)
        spotify.settled(carefree)
        assertEquals(
            "Carefree Highway",
            repository.lastChange.value
                ?.track
                ?.title,
        )

        // Spotify pauses, and YouTube Music's track, settled a minute ago, is shown.
        scheduler.advanceTimeBy(60.seconds)
        val switchedAt = scheduler.currentTime
        spotify.send(state(PlaybackState.STATE_PAUSED, speed = 0f))
        settle()

        val switch = checkNotNull(repository.lastChange.value)
        assertEquals("NewYT", switch.track.title)
        assertEquals(switchedAt, switch.changedAt)
        assertNull(switch.before)
        val times = recorded.filterNotNull().map { it.changedAt }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun anUpdateOfTheSameTrackIsNoChange() {
        val youtube = youtubeMusic()
        youtube.settled(metadata(durationMs = 279_000))
        assertNull(repository.lastChange.value)
    }
}
