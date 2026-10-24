package app.sleevesight.app.session

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.media.session.PlaybackState
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.TELECOM
import app.sleevesight.app.session.Sessions.VIDEO_APP
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.bitmap
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
internal class SessionRepositoryTest : SessionRepositoryFixture() {
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
    fun aPlayerIsSeenOnlyOnceItsSessionReportsMusic() {
        // A video app: a title, no artist. Not seen; only a probe on its metadata.
        val video = FakeSession(VIDEO_APP, metadata = metadata(title = "Episode 1", artist = null))
        system.active = listOf(video)
        repository.start()

        assertEquals(emptySet<String>(), repository.seenPlayers.value)
        assertEquals(1, video.callbacks.size)

        // Another title with no artist leaves it unseen; a track with an artist lists it, and the probe goes.
        video.send(metadata(title = "Episode 2", artist = null))
        assertEquals(emptySet<String>(), repository.seenPlayers.value)
        video.send(metadata(title = "Song", artist = "Singer"))
        assertEquals(setOf(VIDEO_APP), repository.seenPlayers.value)
        assertTrue(video.callbacks.isEmpty())
        assertNull(repository.nowPlaying.value)
    }

    @Test
    fun aProbeIsRemovedWhenItsSessionLeavesTheList() {
        val video = FakeSession(VIDEO_APP, metadata = null)
        system.active = listOf(video)
        repository.start()
        assertEquals(1, video.callbacks.size)

        system.change(emptyList())
        assertTrue(video.callbacks.isEmpty())

        repository.stop()
        assertTrue(video.callbacks.isEmpty())
    }

    @Test
    fun aFollowedPlayerWithNoArtistIsNotSeen() {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(artist = null))
        system.active = listOf(spotify)
        repository.start()

        assertEquals("Sundown", repository.nowPlaying.value?.title)
        assertEquals(emptySet<String>(), repository.seenPlayers.value)

        spotify.settled(metadata())
        assertEquals(setOf(SPOTIFY), repository.seenPlayers.value)
    }

    @Test
    fun aPhoneCallIsNeverReadEvenWhenFollowed() {
        // The caller as the title, and telecom switched on, as an older settings file may have it.
        val call = FakeSession(TELECOM, metadata = metadata(title = "+44 20 7946 0000", artist = "Caller"))
        followed.value = setOf(SPOTIFY, TELECOM)
        system.active = listOf(call)

        repository.start()

        assertTrue(call.callbacks.isEmpty())
        assertNull(repository.nowPlaying.value)
        assertEquals(PlaybackInfo.NONE, repository.playback.value)
        assertEquals(emptySet<String>(), repository.seenPlayers.value)
    }

    @Test
    fun aSessionThatMarksACallIsNeverRead() {
        val voip =
            FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Alice", artist = "Voice call"), marksCall = true)
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        system.active = listOf(voip)

        repository.start()

        assertTrue(voip.callbacks.isEmpty())
        assertNull(repository.nowPlaying.value)
        assertEquals(emptySet<String>(), repository.seenPlayers.value)
    }

    @Test
    fun aFollowedSessionThatTurnsIntoACallShowsNoTrack() {
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        followed.value = setOf(YOUTUBE_MUSIC)
        system.active = listOf(app)
        repository.start()
        assertEquals("Song", repository.nowPlaying.value?.title)
        val shown = recordTracks()

        app.call(true)
        assertNull(repository.nowPlaying.value)
        // The caller's metadata, reported during the call, is never shown nor kept.
        app.settled(metadata(title = "Alice", artist = "Voice call"))
        assertNull(repository.nowPlaying.value)

        // Back to media: the session is ignored for a settle window, whatever it reports.
        app.call(false)
        app.send(metadata(title = "Next song"))
        scheduler.runCurrent()
        assertNull(repository.nowPlaying.value)
        // Then it is read afresh, as any player.
        waitOutTheCall()
        assertEquals("Next song", repository.nowPlaying.value?.title)
        assertTrue(shown.none { it?.title == "Alice" })
    }

    @Test
    fun theFirstReadsStopAtTheCall() {
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Alice", artist = "Voice call"))
        // The mark comes as the metadata is read: the state, the queue and its title are never read.
        app.onMetadataRead = { app.marksCall = true }
        followed.value = setOf(YOUTUBE_MUSIC)
        system.active = listOf(app)

        repository.start()

        assertEquals(1, app.reads)
        assertNull(repository.nowPlaying.value)
        assertEquals(PlaybackInfo.NONE, repository.playback.value)
    }

    @Test
    fun aSessionClearedAsACallDoesNotHideAPausedPlayer() {
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        val spotify = FakeSession(SPOTIFY, playbackState = state(PlaybackState.STATE_PAUSED))
        followed.value = setOf(YOUTUBE_MUSIC, SPOTIFY)
        system.active = listOf(app, spotify)
        repository.start()
        assertEquals("Song", repository.nowPlaying.value?.title)

        app.marksCall = true
        app.send(state(PlaybackState.STATE_PLAYING, positionMs = 5_000))

        assertEquals(sundown, repository.nowPlaying.value)
        assertEquals(SPOTIFY, repository.playback.value.player)
    }

    @Test
    fun aSessionInItsWaitAfterACallIsNotReadWhenAttachedAgain() {
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        followed.value = setOf(YOUTUBE_MUSIC)
        system.active = listOf(app)
        repository.start()
        app.marksCall = true
        app.send(metadata(title = "Alice", artist = "Voice call"))
        app.marksCall = false
        val reads = app.reads

        // Attached afresh, as when the players followed change, within the wait.
        followed.value = emptySet()
        followed.value = setOf(YOUTUBE_MUSIC)
        scheduler.runCurrent()

        assertEquals(reads, app.reads)
        assertNull(repository.nowPlaying.value)
    }

    @Test
    fun aPlayerIsShownAtOnceWithItsStateAfterTheWait() {
        // YouTube Music's order: the state before the metadata, both within the wait.
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        val spotify = FakeSession(SPOTIFY, playbackState = state(PlaybackState.STATE_PAUSED))
        followed.value = setOf(YOUTUBE_MUSIC, SPOTIFY)
        system.active = listOf(app, spotify)
        repository.start()
        app.call(true)
        assertEquals(sundown, repository.nowPlaying.value)

        app.call(false)
        app.send(state(PlaybackState.STATE_PLAYING, positionMs = 2_000))
        app.send(metadata(title = "After"))
        scheduler.runCurrent()
        assertEquals(sundown, repository.nowPlaying.value)

        // No report after the wait: the session is read afresh, its state with it.
        waitOutTheCall()
        assertEquals("After", repository.nowPlaying.value?.title)
        assertEquals(YOUTUBE_MUSIC, repository.playback.value.player)
        assertEquals(PlaybackStatus.PLAYING, repository.playback.value.status)
    }

    @Test
    fun thePlayersScreenOfACallIsNotRead() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val screen = PendingIntent.getActivity(context, 1, Intent(Intent.ACTION_MAIN), PendingIntent.FLAG_IMMUTABLE)
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"), sessionActivity = screen)
        val spotify = FakeSession(SPOTIFY, playbackState = state(PlaybackState.STATE_PAUSED))
        followed.value = setOf(YOUTUBE_MUSIC, SPOTIFY)
        system.active = listOf(app, spotify)
        repository.start()
        assertSame(screen, repository.playerActivity())

        // Spotify's report still settling: once the call is found, Spotify is not yet shown in its
        // place, so the session that was shown stays the last one shown.
        spotify.send(state(PlaybackState.STATE_PAUSED, positionMs = 1))
        app.marksCall = true
        val reads = app.reads

        assertNull(repository.playerActivity())
        assertEquals(reads, app.reads)

        // In the wait after the call, too.
        app.call(false)
        assertNull(repository.playerActivity())
        assertEquals(reads, app.reads)
        waitOutTheCall()
        assertSame(screen, repository.playerActivity())
    }

    @Test
    fun aCallDuringTheWaitStartsItAgain() {
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        followed.value = setOf(YOUTUBE_MUSIC)
        system.active = listOf(app)
        repository.start()
        val half = SessionRepository.SETTLE.inWholeMilliseconds / 2

        app.call(true)
        app.call(false)
        val reads = app.reads
        scheduler.advanceTimeBy(half)
        app.call(true)
        app.call(false)
        scheduler.advanceTimeBy(half + 1)
        scheduler.runCurrent()
        assertEquals(reads, app.reads)
        assertNull(repository.nowPlaying.value)

        scheduler.advanceTimeBy(half)
        scheduler.runCurrent()
        assertEquals("Song", repository.nowPlaying.value?.title)
    }

    @Test
    fun aSessionDestroyedDuringTheWaitLeavesNoWait() {
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        followed.value = setOf(YOUTUBE_MUSIC)
        system.active = listOf(app)
        repository.start()
        app.call(true)
        app.call(false)
        val reads = app.reads

        app.destroy()
        scheduler.runCurrent()
        assertEquals(reads, app.reads)
        assertNull(repository.nowPlaying.value)

        // Its wait went with it: listed again within what was left of it, the same session is
        // read at once, not after a wait.
        system.change(listOf(app))
        assertEquals("Song", repository.nowPlaying.value?.title)
    }

    @Test
    fun aSessionLeavingTheListDuringTheWaitLeavesNoWait() {
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        followed.value = setOf(YOUTUBE_MUSIC)
        system.active = listOf(app)
        repository.start()
        app.call(true)
        app.call(false)
        val reads = app.reads

        system.change(emptyList())
        scheduler.runCurrent()
        assertEquals(reads, app.reads)
        assertNull(repository.nowPlaying.value)

        // Its wait went with it: listed again within what was left of it, the same session is
        // read at once, not after a wait.
        system.change(listOf(app))
        assertEquals("Song", repository.nowPlaying.value?.title)
    }

    @Test
    fun callerMetadataIsNotShownWhileMarkedNorDuringTheWait() {
        // The usage flips before the callback for it arrives: the metadata callback checks first.
        val app = FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        followed.value = setOf(YOUTUBE_MUSIC)
        system.active = listOf(app)
        repository.start()
        val shown = recordTracks()

        app.marksCall = true
        app.settled(metadata(title = "Alice", artist = "Voice call"))
        app.marksCall = false
        app.send(state(PlaybackState.STATE_PLAYING, positionMs = 1_000))
        scheduler.runCurrent()

        assertTrue(shown.none { it?.title == "Alice" })
        assertNull(repository.nowPlaying.value)
    }

    @Test
    fun anUnfollowedPlayerIsListedAfterACallEnds() {
        val video = FakeSession(VIDEO_APP, metadata = metadata(artist = null))
        system.active = listOf(video)
        repository.start()

        video.call(true)
        video.send(metadata(title = "Song", artist = "Singer"))
        assertEquals(emptySet<String>(), repository.seenPlayers.value)

        // Music within the wait is not taken; the session is checked afresh after it.
        video.call(false)
        video.send(metadata(title = "Song", artist = "Singer"))
        scheduler.runCurrent()
        assertEquals(emptySet<String>(), repository.seenPlayers.value)
        waitOutTheCall()
        assertEquals(setOf(VIDEO_APP), repository.seenPlayers.value)
        assertTrue(video.callbacks.isEmpty())
    }

    @Test
    fun aProbeReadsNoMetadataOfASessionMarkingACall() {
        val video = FakeSession(VIDEO_APP, metadata = metadata(artist = null))
        system.active = listOf(video)
        repository.start()

        // The usage flips without its own callback first: the metadata callback checks it.
        video.marksCall = true
        val reads = video.reads
        video.send(metadata(title = "Alice", artist = "Voice call"))

        assertEquals(reads, video.reads)
        assertEquals(emptySet<String>(), repository.seenPlayers.value)
    }

    @Test
    fun aSessionMarkedACallAsItsProbeRegistersIsNotRead() {
        val caller = FakeSession(VIDEO_APP, metadata = metadata(title = "Alice", artist = "Voice call"))
        caller.onRegister = { caller.marksCall = true }
        system.active = listOf(caller)

        repository.start()

        assertEquals(0, caller.reads)
        assertEquals(emptySet<String>(), repository.seenPlayers.value)
    }

    @Test
    fun aPlayerAlreadySeenHasNoMoreMetadataRead() {
        val other = FakeSession(VIDEO_APP)
        system.active = listOf(other)
        repository.start()
        assertEquals(setOf(VIDEO_APP), repository.seenPlayers.value)
        val reads = other.metadataReads

        system.change(listOf(other))
        system.change(listOf(other))

        assertEquals(reads, other.metadataReads)
        assertTrue(other.callbacks.isEmpty())
    }

    @Test
    fun aPlayerSeenThroughOneSessionGetsNoProbeOnItsOther() {
        val first = FakeSession(VIDEO_APP, metadata = metadata(artist = null))
        val second = FakeSession(VIDEO_APP, metadata = metadata(artist = null))
        // Music arrives as the first session's probe registers.
        first.onRegister = { first.metadata = metadata() }
        system.active = listOf(first, second)

        repository.start()

        assertEquals(setOf(VIDEO_APP), repository.seenPlayers.value)
        assertTrue(first.callbacks.isEmpty())
        assertTrue(second.callbacks.isEmpty())
        assertEquals(0, second.metadataReads)
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
    fun thePlayerActivityIsThatOfThePlayerWhoseTrackIsShown() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        fun activity(code: Int) =
            PendingIntent.getActivity(context, code, Intent(Intent.ACTION_MAIN), PendingIntent.FLAG_IMMUTABLE)
        val youtube = activity(1)
        val spotify = activity(2)
        val paused =
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = metadata(title = "Other"),
                playbackState = state(PlaybackState.STATE_PAUSED),
                sessionActivity = youtube,
            )
        val playing = FakeSession(SPOTIFY, sessionActivity = spotify)
        assertNull(repository.playerActivity())
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        system.active = listOf(paused, playing)
        repository.start()
        assertSame(spotify, repository.playerActivity())

        playing.settled(state(PlaybackState.STATE_PAUSED))
        assertSame(youtube, repository.playerActivity())

        repository.stop()
        assertNull(repository.playerActivity())
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
    fun remoteArtWaitsWhileLookupsAreNotAllowedAndTheCurrentTracksLoadsOnReturn() {
        artAllowed.value = false
        val spotify = FakeSession(SPOTIFY, metadata = metadata(mediaId = "1", artUri = "https://cdn/1"))
        system.active = listOf(spotify)
        repository.start()
        spotify.settled(metadata(mediaId = "2", title = "Carefree Highway", artUri = "https://cdn/2"))
        assertEquals(emptyList<String>(), fetches.map { it.first })

        // The first track's load was cancelled with its track; only the one shown is fetched.
        artAllowed.value = true
        settle()
        assertEquals(listOf("https://cdn/2"), fetches.map { it.first })
        fetches.single().second.complete(png(Color.GREEN))
        assertEquals(Color.GREEN, repository.art.value?.getPixel(0, 0))
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

    @Test
    fun stopClearsTheLastChange() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()
        spotify.settled(carefree)
        assertEquals(
            "Carefree Highway",
            repository.lastChange.value
                ?.track
                ?.title,
        )

        repository.stop()
        assertNull(repository.lastChange.value)

        repository.start()
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
}
