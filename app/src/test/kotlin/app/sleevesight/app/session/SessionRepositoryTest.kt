package app.sleevesight.app.session

import android.graphics.Color
import android.media.session.PlaybackState
import android.util.Log
import app.sleevesight.app.session.Sessions.SPOTIFY
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
