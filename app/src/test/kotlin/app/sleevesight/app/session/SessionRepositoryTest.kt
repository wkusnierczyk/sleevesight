package app.sleevesight.app.session

import android.graphics.Bitmap
import android.graphics.Color
import android.media.session.PlaybackState
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.bitmap
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.model.NowPlaying
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionRepositoryTest {
    private val scheduler = TestCoroutineScheduler()

    /** Runs callbacks at once, and the settle window in virtual time ([settle]). */
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
    private val acknowledged = MutableStateFlow(true)
    private val followed = MutableStateFlow(setOf(SPOTIFY))
    private val system = FakeActiveSessions()

    /** What an `http` art fetch waits for; each fetch takes the next one. */
    private val fetches = mutableListOf<Pair<String, CompletableDeferred<ByteArray?>>>()
    private val http = HttpArt { uri -> CompletableDeferred<ByteArray?>().also { fetches += uri to it }.await() }
    private val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver

    private val repository =
        SessionRepository(
            system,
            acknowledged,
            ArtLoader(resolver, http, Dispatchers.Unconfined),
            scope,
            followed,
            clock = { scheduler.currentTime },
        )

    private val sundown = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = SPOTIFY)

    @After
    fun tearDown() = scope.cancel()

    /** Lets the settle window pass. */
    private fun settle() = scheduler.advanceUntilIdle()

    private fun FakeSession.settled(metadata: android.media.MediaMetadata) {
        send(metadata)
        settle()
    }

    private fun FakeSession.settled(state: PlaybackState) {
        send(state)
        settle()
    }

    private fun ignoredLines() =
        ShadowLog.getLogsForTag("Sleevesight").filter {
            it.type == Log.INFO &&
                it.msg.startsWith("Ignored")
        }

    @Test
    fun nothingIsReadUntilTheFirstRunIsAcknowledged() {
        acknowledged.value = false
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)

        repository.start()

        assertEquals(0, system.reads)
        assertNull(system.listener)
        assertTrue(spotify.callbacks.isEmpty())
        assertNull(repository.nowPlaying.value)
        assertEquals(PlaybackInfo.NONE, repository.playback.value)
        assertEquals(emptySet<String>(), repository.seenPlayers.value)

        acknowledged.value = true

        assertEquals(1, system.reads)
        assertEquals(1, spotify.callbacks.size)
        assertEquals(sundown, repository.nowPlaying.value)
    }

    @Test
    fun theStateIsRebuiltFromTheActiveSessionsOnStart() {
        // As after process death: the player is mid-track, and no callback has fired yet.
        system.active =
            listOf(
                FakeSession(
                    SPOTIFY,
                    metadata = metadata(mediaId = "spotify:track:1", durationMs = 223_000),
                    playbackState = state(positionMs = 61_000, updatedAt = 500, queueItem = 9),
                    queue = listOf(queueItem(10, "Carefree Highway")),
                    queueTitle = "Play Queue",
                ),
            )

        repository.start()

        assertEquals(
            sundown.copy(mediaId = "spotify:track:1", duration = 223.seconds, queueItemId = 9),
            repository.nowPlaying.value,
        )
        assertEquals(PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, 61.seconds, 500, 1f), repository.playback.value)
        assertEquals(
            SessionQueue("Play Queue", listOf(QueueEntry(10, "Carefree Highway", null, null))),
            repository.queue.value,
        )
    }

    @Test
    fun playersNotFollowedAreDroppedAndLoggedOnce() {
        val youtube = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Other"))
        val spotify = FakeSession(SPOTIFY, playbackState = state(PlaybackState.STATE_PAUSED))
        system.active = listOf(youtube, spotify)

        repository.start()
        system.change(listOf(youtube, spotify))
        youtube.settled(state(PlaybackState.STATE_PLAYING))

        assertEquals(sundown, repository.nowPlaying.value)
        assertTrue(youtube.callbacks.isEmpty())
        assertEquals(1, ignoredLines().size)
        assertTrue(ignoredLines().single().msg.contains(YOUTUBE_MUSIC))
        assertEquals(setOf(YOUTUBE_MUSIC, SPOTIFY), repository.seenPlayers.value)
    }

    @Test
    fun aPlayerSwitchedOnIsFollowedAndOneSwitchedOffIsDropped() {
        val youtube = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Other"))
        val spotify = FakeSession(SPOTIFY, playbackState = state(PlaybackState.STATE_PAUSED))
        system.active = listOf(youtube, spotify)
        repository.start()

        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        assertEquals("Other", repository.nowPlaying.value?.title)

        followed.value = setOf(YOUTUBE_MUSIC)
        assertTrue(spotify.callbacks.isEmpty())
        assertEquals(YOUTUBE_MUSIC, repository.nowPlaying.value?.player)

        followed.value = emptySet()
        assertNull(repository.nowPlaying.value)
        assertTrue(youtube.callbacks.isEmpty())
    }

    @Test
    fun thePlayingSessionIsShownOverAPausedOneRankedHigher() {
        val paused =
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = metadata(title = "Other"),
                playbackState = state(PlaybackState.STATE_PAUSED),
            )
        val playing = FakeSession(SPOTIFY)
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        system.active = listOf(paused, playing)
        repository.start()
        assertEquals(SPOTIFY, repository.nowPlaying.value?.player)

        playing.settled(state(PlaybackState.STATE_PAUSED))
        // Neither plays: the one the system ranks first.
        assertEquals(YOUTUBE_MUSIC, repository.nowPlaying.value?.player)
    }

    @Test
    fun callbacksUpdateTheTrackThePlaybackAndTheQueue() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()

        spotify.settled(metadata(title = "Carefree Highway"))
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)

        val track = repository.nowPlaying.value
        spotify.settled(state(PlaybackState.STATE_PAUSED, positionMs = 30_000, speed = 0f))
        assertSame(track, repository.nowPlaying.value)
        assertEquals(PlaybackStatus.PAUSED, repository.playback.value.status)

        spotify.sendQueue(listOf(queueItem(1, "Rainy Day People")), "Play Queue")
        assertEquals("Play Queue", repository.queue.value?.title)
        assertEquals(
            listOf("Rainy Day People"),
            repository.queue.value
                ?.items
                ?.map { it.title },
        )
    }

    @Test
    fun aSessionThatEndsLeavesNothingPlaying() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()

        spotify.destroy()
        assertNull(repository.nowPlaying.value)
        assertEquals(PlaybackInfo.NONE, repository.playback.value)
        assertTrue(spotify.callbacks.isEmpty())

        system.change(listOf(FakeSession(SPOTIFY, metadata = metadata(title = "Carefree Highway"))))
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        system.change(emptyList())
        assertNull(repository.nowPlaying.value)
    }

    @Test
    fun stopReleasesEverythingAndStartRebuilds() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()
        repository.start()
        assertEquals(1, spotify.callbacks.size)

        repository.stop()
        assertNull(system.listener)
        assertTrue(spotify.callbacks.isEmpty())
        assertNull(repository.nowPlaying.value)

        repository.start()
        assertEquals(sundown, repository.nowPlaying.value)
    }

    @Test
    fun withdrawingTheAcknowledgementDetaches() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()

        acknowledged.value = false
        assertNull(system.listener)
        assertTrue(spotify.callbacks.isEmpty())
        assertNull(repository.nowPlaying.value)
    }

    @Test
    fun theSessionsBitmapIsTheArtAtOnce() {
        val red = bitmap(Color.RED)
        val spotify = FakeSession(SPOTIFY, metadata = metadata(albumArt = red, albumArtUri = "https://cdn/1"))
        system.active = listOf(spotify)
        repository.start()

        assertSame(red, repository.art.value)
        assertEquals(emptyList<String>(), fetches.map { it.first })
    }

    @Test
    fun artIsKeptThroughAnUpdateOfTheSameTrackAndClearedOnAChange() {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(mediaId = "1", artUri = "https://cdn/1"))
        system.active = listOf(spotify)
        repository.start()
        val first = png(Color.RED)
        fetches.single().second.complete(first)
        val shown = repository.art.value
        assertEquals(Color.RED, shown?.getPixel(0, 0))

        // The same track with new art: the old art stays until the new one arrives.
        spotify.settled(metadata(mediaId = "1", artUri = "https://cdn/1-large"))
        assertSame(shown, repository.art.value)
        fetches.last().second.complete(png(Color.BLUE))
        assertEquals(Color.BLUE, repository.art.value?.getPixel(0, 0))

        // Another track: no art until its own arrives, and a late answer for a superseded one is dropped.
        spotify.settled(metadata(mediaId = "2", title = "Carefree Highway", artUri = "https://cdn/2"))
        assertNull(repository.art.value)
        spotify.settled(metadata(mediaId = "3", title = "Rainy Day People", artUri = "https://cdn/3"))
        fetches.first { it.first == "https://cdn/2" }.second.complete(png(Color.GREEN))
        assertNull(repository.art.value)
        fetches.last().second.complete(png(Color.YELLOW))
        assertEquals(Color.YELLOW, repository.art.value?.getPixel(0, 0))
    }

    @Test
    fun republishedMetadataDoesNotReloadTheArt() {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(mediaId = "1", artUri = "https://cdn/1"))
        system.active = listOf(spotify)
        repository.start()
        fetches.single().second.complete(png(Color.RED))

        spotify.settled(metadata(mediaId = "1", artUri = "https://cdn/1"))
        assertEquals(1, fetches.size)
    }

    /** Every value [SessionRepository.nowPlaying] takes from now on, the current one first. */
    private fun recordTracks(): List<NowPlaying?> {
        val tracks = mutableListOf<NowPlaying?>()
        scope.launch { repository.nowPlaying.collect { tracks += it } }
        return tracks
    }

    private fun changes(tracks: List<NowPlaying?>) =
        tracks.filterNotNull().zipWithNext().count { (a, b) -> !TrackIdentity.same(a, b) }

    private val carefree = metadata(title = "Carefree Highway")

    private fun fetchesFor(uri: String) = fetches.filter { it.first == uri }.map { it.second }

    /** A YouTube Music session playing [metadata] as [item], with its "Up next" published or not. */
    private fun youtubeMusic(
        queue: Boolean = false,
        metadata: android.media.MediaMetadata? = metadata(),
        item: Long = 4,
    ): FakeSession {
        followed.value = setOf(YOUTUBE_MUSIC)
        val session =
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = metadata,
                playbackState = state(queueItem = item),
                queue = if (queue) listOf(queueItem(4, "Sundown"), queueItem(5, "Carefree Highway")) else null,
            )
        system.active = listOf(session)
        repository.start()
        return session
    }

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
    fun anUpdateOfTheSameTrackIsNoChange() {
        val youtube = youtubeMusic()
        youtube.settled(metadata(durationMs = 279_000))
        assertNull(repository.lastChange.value)
    }

    @Test
    fun aChangeWhileTheCallbackIsRegisteredIsNotLost() {
        val spotify = FakeSession(SPOTIFY)
        spotify.onRegister = {
            spotify.metadata = carefree
            spotify.playbackState = state(PlaybackState.STATE_PAUSED)
        }
        system.active = listOf(spotify)
        repository.start()

        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        assertEquals(PlaybackStatus.PAUSED, repository.playback.value.status)
    }

    @Test
    fun aListDispatchedAfterStopRegistersNothing() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()
        val late = checkNotNull(system.listener)
        repository.stop()

        late(listOf(spotify))
        assertTrue(spotify.callbacks.isEmpty())
        assertNull(repository.nowPlaying.value)
    }

    @Test
    fun anotherTrackWithTheSameArtUriReloadsAndTheOldLoadNeverLands() {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(mediaId = "1", artUri = "https://cdn/now"))
        system.active = listOf(spotify)
        repository.start()
        val pending = fetchesFor("https://cdn/now").single()

        spotify.settled(metadata(mediaId = "2", title = "Carefree Highway", artUri = "https://cdn/now"))
        assertNull(repository.art.value)
        val reload = fetchesFor("https://cdn/now")
        assertEquals(2, reload.size)

        pending.complete(png(Color.RED))
        assertNull(repository.art.value)
        reload.last().complete(png(Color.BLUE))
        assertEquals(Color.BLUE, repository.art.value?.getPixel(0, 0))
    }

    @Test
    fun aFailedLoadForTheSameTrackKeepsTheArtShown() {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(mediaId = "1", artUri = "https://cdn/1"))
        system.active = listOf(spotify)
        repository.start()
        fetches.single().second.complete(png(Color.RED))
        val shown = repository.art.value

        spotify.settled(metadata(mediaId = "1", artUri = "https://cdn/1-large"))
        fetches.last().second.complete(null)
        assertSame(shown, repository.art.value)
    }

    private fun png(color: Int): ByteArray =
        java.io
            .ByteArrayOutputStream()
            .also {
                bitmap(
                    color,
                ).compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
}
