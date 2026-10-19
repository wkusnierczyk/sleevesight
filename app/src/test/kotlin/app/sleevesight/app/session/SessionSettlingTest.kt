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
    fun aNewTracksStateWellBeforeItsMetadataIsWhereTheChangeBegan() {
        // The state names item 5 under the old title and settles alone; the title comes 500 ms
        // later. The change began at the state, after Sundown's last state, not at the title.
        val youtube = youtubeMusic()
        youtube.settled(state(positionMs = 200_000, updatedAt = 7, queueItem = 4))
        val began = scheduler.currentTime
        youtube.send(state(positionMs = 0, updatedAt = began, queueItem = 5))
        scheduler.advanceTimeBy(500.milliseconds)
        youtube.send(carefree)
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals("Carefree Highway", change.track.title)
        assertEquals(began, change.changedAt)
        assertEquals(PlaybackInfo(YOUTUBE_MUSIC, PlaybackStatus.PLAYING, 200.seconds, 7, 1f), change.before)
    }

    @Test
    fun aLiveRecordingAfterTheStudioOneIsAChange() {
        // The same title and artists, on another album: another recording.
        val youtube = youtubeMusic(metadata = metadata(album = "Sundown"))
        val changes = recordChanges()

        youtube.send(state(queueItem = 5))
        youtube.settled(metadata(album = "Gord's Gold (Live)"))

        assertEquals(listOf("Gord's Gold (Live)"), changes.map { it.track.album })
    }

    @Test
    fun aTitleWithNoArtistsBetweenTwoArtistsIsOneChange() {
        // Intro by X; Intro with no artists, settled alone; then Intro by Y.
        val youtube = youtubeMusic(metadata = metadata(title = "Intro", artist = "X"))
        val changes = recordChanges()

        youtube.settled(metadata(title = "Intro", artist = null))
        youtube.settled(metadata(title = "Intro", artist = "Y"))
        youtube.settled(metadata(title = "Intro", artist = null))
        youtube.settled(metadata(title = "Intro", artist = "Y"))

        assertEquals(listOf("Y"), changes.map { it.track.artists })
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
    fun aSongQueuedTwiceAndPlayedBackToBackIsOnePlay() {
        // The queue item is carried, not read for identity: one play, its item moved on.
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.settled(state(queueItem = 5))
        youtube.settled(metadata())

        assertEquals(0, changes(tracks))
        assertEquals(listOf(4L, 5L), tracks.map { it?.queueItemId })
    }

    @Test
    fun aSongQueuedTwiceWithAnotherTrackBetweenIsTwoPlays() {
        val youtube = youtubeMusic()
        val tracks = recordTracks()

        youtube.send(state(queueItem = 5))
        youtube.settled(carefree)
        youtube.send(state(queueItem = 6))
        youtube.settled(metadata())

        assertEquals(2, changes(tracks))
        assertEquals(listOf("Sundown", "Carefree Highway", "Sundown"), tracks.map { it?.title })
        assertEquals(listOf(4L, 5L, 6L), tracks.map { it?.queueItemId })
    }

    @Test
    fun decoratedQueueTitlesAreNotCompared() {
        // The queue names the copies "Sundown (Remastered)"; the metadata says "Sundown": the
        // change into the first is seen, and the second, played after it, is the same play.
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

        assertEquals(1, changes(tracks))
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

        // The previous track's item is carried until the state names the new one.
        assertEquals(1, changes(tracks))
        assertEquals(listOf(4L, 4L, 5L), tracks.map { it?.queueItemId })
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
    fun theLongestWaitDoesNotCutANewTracksStateFromItsMetadata() {
        // Reports less than 300 ms apart for a second (a seek bar scrubbed), then the new track's
        // state just before the longest wait runs out, and its metadata just after.
        val youtube = youtubeMusic()
        val tracks = recordTracks()
        val start = scheduler.currentTime

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
        assertEquals(start + 990, change.changedAt)
        assertEquals(PlaybackInfo(YOUTUBE_MUSIC, PlaybackStatus.PLAYING, 100_750.milliseconds, 750, 1f), change.before)
    }

    @Test
    fun theLongestWaitDoesNotCutTheSecondOfTwoChangesInOneBurst() {
        // *Next* pressed, reports less than 300 ms apart, and *next* again as the longest wait runs out.
        val youtube = youtubeMusic()
        val tracks = recordTracks()
        val began = scheduler.currentTime

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
        assertEquals(began, repository.lastChange.value?.changedAt)
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

    /** Moves virtual time on to [ms], running what is due before it. */
    private fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    @Test
    fun castboxsRecordedChangeBeganAtTheNewEpisodesFirstState() {
        // Recorded (`.dev/probe.jsonl`): the new episode's state falls back to 0 on the same queue
        // item, 0, and its metadata follows 90 ms later.
        val castbox = "fm.castbox.audiobook.radio.podcast"
        followed.value = setOf(castbox)
        at(1_148_170_321)
        val session =
            FakeSession(
                castbox,
                metadata =
                    metadata(
                        title = "How to build muscle & stay strong at any age",
                        artist = "ZOE",
                        mediaId = "1008811046",
                        durationMs = 3_720_097,
                    ),
                playbackState =
                    state(PlaybackState.STATE_PAUSED, positionMs = 1_878_080, updatedAt = 1_148_170_320, queueItem = 0),
            )
        system.active = listOf(session)
        repository.start()

        at(1_148_173_102)
        repeat(2) { session.send(state(PlaybackState.STATE_PAUSED, updatedAt = 1_148_173_101, queueItem = 0)) }
        at(1_148_173_108)
        session.send(state(PlaybackState.STATE_BUFFERING, updatedAt = 1_148_173_106, queueItem = 0))
        at(1_148_173_150)
        session.send(state(PlaybackState.STATE_BUFFERING, updatedAt = 1_148_173_144, queueItem = 0))
        at(1_148_173_192)
        session.send(
            metadata(
                title = "French supermarkets say fridges targeted in cyberattack",
                artist = "BBC World Service",
                mediaId = "1011324312",
                durationMs = 1_927_000,
            ),
        )
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals("1011324312", change.track.mediaId)
        assertEquals(1_148_173_102L, change.changedAt)
        assertEquals(
            PlaybackInfo(castbox, PlaybackStatus.PAUSED, 1_878_080.milliseconds, 1_148_170_320, 1f),
            change.before,
        )
    }

    @Test
    fun aNewTracksFirstStateWithNoItemBeforeItsMetadataIsWhereTheChangeBegan() {
        val youtube = youtubeMusic(item = -1)
        youtube.settled(state(positionMs = 200_000, updatedAt = 100, queueItem = -1))

        at(5_300)
        youtube.send(state(positionMs = 0, updatedAt = 5_300, queueItem = -1))
        at(5_350)
        youtube.send(carefree)
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals("Carefree Highway", change.track.title)
        assertEquals(5_300L, change.changedAt)
        assertEquals(PlaybackInfo(YOUTUBE_MUSIC, PlaybackStatus.PLAYING, 200.seconds, 100, 1f), change.before)
    }

    @Test
    fun aSeekToMidTrackJustBeforeANewTitleIsNotWhereTheChangeBegan() {
        val spotify = FakeSession(SPOTIFY, playbackState = state(positionMs = 30_000, updatedAt = 5_000))
        system.active = listOf(spotify)
        repository.start()

        at(10_000)
        spotify.send(state(positionMs = 5_000, updatedAt = 10_000))
        at(10_200)
        spotify.send(carefree)
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals(10_200L, change.changedAt)
        assertEquals(PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, 5.seconds, 10_000, 1f), change.before)
    }

    @Test
    fun aNewItemAfterASeekBackIsWhereTheChangeBegan() {
        val youtube = youtubeMusic()
        youtube.settled(state(positionMs = 239_000, updatedAt = 1_000, queueItem = 4))

        at(10_000)
        youtube.send(state(positionMs = 30_000, updatedAt = 10_000, queueItem = 4))
        at(10_200)
        youtube.send(state(positionMs = 0, updatedAt = 10_200, queueItem = 5))
        at(10_250)
        youtube.send(carefree)
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals(5L, change.track.queueItemId)
        assertEquals(10_200L, change.changedAt)
        assertEquals(PlaybackInfo(YOUTUBE_MUSIC, PlaybackStatus.PLAYING, 30.seconds, 10_000, 1f), change.before)
    }

    @Test
    fun aNewItemJustAfterARestartIsWhereTheChangeBegan() {
        // The track restarted (back to 0, the same item), then *next*: the new item marks the change.
        val youtube = youtubeMusic()
        youtube.settled(state(positionMs = 239_000, updatedAt = 1_000, queueItem = 4))

        at(10_000)
        youtube.send(state(positionMs = 0, updatedAt = 10_000, queueItem = 4))
        at(10_200)
        youtube.send(state(positionMs = 0, updatedAt = 10_200, queueItem = 5))
        at(10_250)
        youtube.send(carefree)
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals(10_200L, change.changedAt)
        assertEquals(PlaybackInfo(YOUTUBE_MUSIC, PlaybackStatus.PLAYING, 0.seconds, 10_000, 1f), change.before)
    }

    @Test
    fun castboxStalledAtTheStartIsNotWhereTheChangeBegan() {
        // Recorded (`.dev/probe.jsonl`): `PLAYING` at 0, and again 4 s later, still at 0; then, in
        // this test, a new episode's metadata 100 ms after the second.
        val castbox = "fm.castbox.audiobook.radio.podcast"
        followed.value = setOf(castbox)
        at(1_148_239_099)
        val session =
            FakeSession(
                castbox,
                metadata =
                    metadata(
                        title = "Houthis attack Saudi airport for third time in four days",
                        mediaId = "1011031754",
                    ),
                playbackState = state(updatedAt = 1_148_239_036, queueItem = 1),
            )
        system.active = listOf(session)
        repository.start()

        at(1_148_243_304)
        session.send(state(updatedAt = 1_148_243_292, queueItem = 1))
        at(1_148_243_404)
        session.send(
            metadata(title = "French supermarkets say fridges targeted in cyberattack", mediaId = "1011324312"),
        )
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals(1_148_243_404L, change.changedAt)
        assertEquals(PlaybackInfo(castbox, PlaybackStatus.PLAYING, 0.seconds, 1_148_243_292, 1f), change.before)
    }

    @Test
    fun aTitleChangingEvery250MsStillSettlesWithinTheLongestBurst() {
        val youtube = youtubeMusic()
        val start = scheduler.currentTime
        val longest = SessionRepository.LONGEST_BURST.inWholeMilliseconds
        for (sent in 0L..longest step 250) {
            at(start + sent)
            youtube.send(metadata(title = "Title $sent"))
        }
        at(start + longest + 1)

        assertEquals("Title $longest", repository.nowPlaying.value?.title)
        assertEquals(start, repository.lastChange.value?.changedAt)
    }

    /** Spotify, shown and playing, ranked over YouTube Music playing [youtubeState] on "OldYT". */
    private fun spotifyOverYouTubeMusic(youtubeState: PlaybackState): Pair<FakeSession, FakeSession> {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY)
        val youtube = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "OldYT"), playbackState = youtubeState)
        system.active = listOf(spotify, youtube)
        repository.start()
        return spotify to youtube
    }

    private fun recordChanges(): List<SessionChange> {
        val changes = mutableListOf<SessionChange>()
        scope.launch { repository.lastChange.collect { change -> change?.let { changes += it } } }
        return changes
    }

    @Test
    fun aSwitchCompletedByAHiddenBurstThatBeganEarlierIsTimedAtTheSwitch() {
        // YouTube Music, hidden, changes track and keeps reporting; Spotify changes track after
        // it began, settles, and pauses; YouTube Music then settles, completing the switch.
        val (spotify, youtube) = spotifyOverYouTubeMusic(state(queueItem = 4))
        val changes = recordChanges()

        at(10_000)
        youtube.send(state(queueItem = 5))
        youtube.send(metadata(title = "NewYT"))
        at(10_100)
        spotify.send(carefree)
        for (sent in listOf(10_250L, 10_500L, 10_750L)) {
            at(sent)
            youtube.send(state(positionMs = sent - 10_000, updatedAt = sent, queueItem = 5))
            if (sent == 10_500L) spotify.send(state(PlaybackState.STATE_PAUSED, speed = 0f))
        }
        at(11_001)

        assertEquals(listOf("Carefree Highway", "NewYT"), changes.map { it.track.title })
        assertEquals(listOf(10_100L, 11_000L), changes.map { it.changedAt })
        assertNull(changes.last().before)
    }

    @Test
    fun aSwitchToAPausedPlayerThatThenPlaysIsTimedAtTheSwitch() {
        // YouTube Music, paused, gets new metadata; Spotify changes track; YouTube Music plays
        // the same item, Spotify pauses, and YouTube Music's settle completes the switch.
        val (spotify, youtube) = spotifyOverYouTubeMusic(state(PlaybackState.STATE_PAUSED, speed = 0f, queueItem = 4))
        val changes = recordChanges()

        at(10_000)
        youtube.send(metadata(title = "NewYT"))
        at(10_010)
        spotify.send(carefree)
        at(10_250)
        youtube.send(state(queueItem = 4))
        at(10_320)
        spotify.send(state(PlaybackState.STATE_PAUSED, speed = 0f))
        settle()

        assertEquals(listOf("Carefree Highway", "NewYT"), changes.map { it.track.title })
        assertEquals(listOf(10_010L, 10_550L), changes.map { it.changedAt })
        assertNull(changes.last().before)
    }

    @Test
    fun anUpdateOfTheSameTrackIsNoChange() {
        val youtube = youtubeMusic()
        youtube.settled(metadata(durationMs = 279_000))
        assertNull(repository.lastChange.value)
    }
}
