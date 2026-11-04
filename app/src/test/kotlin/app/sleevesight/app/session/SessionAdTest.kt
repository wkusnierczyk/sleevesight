package app.sleevesight.app.session

import android.graphics.Color
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.advertised
import app.sleevesight.app.session.Sessions.bitmap
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.TrackChange
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

/**
 * An ad in the session layer (#880): published as no track, with [SessionRepository.ad] in its place,
 * so nothing that reads the tracks (the pipeline, read-aloud, the preparation, the history, skip
 * detection) sees it; the track after it is a new play, published as usual. The ads are synthetic
 * (`AdDetectionTest`).
 */
@RunWith(RobolectricTestRunner::class)
internal class SessionAdTest : SessionRepositoryFixture() {
    private val ad =
        advertised(
            metadata(
                title = "tetesept",
                artist = "Advertisement • 2 of 3",
                durationMs = 28_000,
                albumArt = bitmap(Color.YELLOW),
            ),
        )
    private val adQueue =
        listOf(queueItem(1, "Sundown", mediaId = "spotify:track:1"), queueItem(2, "tetesept", mediaId = "spotify:ad:1"))
    private val carefreeTrack = metadata(title = "Carefree Highway", mediaId = "spotify:track:2", durationMs = 230_000)

    private fun spotify(): FakeSession {
        val spotify =
            FakeSession(
                SPOTIFY,
                metadata = metadata(mediaId = "spotify:track:1", durationMs = 225_000, albumArt = bitmap(Color.BLUE)),
            )
        system.active = listOf(spotify)
        repository.start()
        return spotify
    }

    @Test
    fun `an ad is published as no track, with no art, and the ad in its place`() {
        val spotify = spotify()
        spotify.settled(ad)
        assertNull(repository.nowPlaying.value)
        assertNull("the pipeline gets no report", repository.trackChanges.value)
        assertNull("the history gets none", repository.playing.value)
        assertNull("not the ad's image", repository.art.value)
        assertEquals(AdPlaying(SPOTIFY, 28.seconds), repository.ad.value)
        assertEquals("the bar still moves", PlaybackStatus.PLAYING, repository.playback.value.status)
    }

    @Test
    fun `the track after an ad is a new play, published as usual, and the ad is gone`() {
        val spotify = spotify()
        val before = repository.nowPlaying.value
        spotify.settled(ad)
        spotify.settled(carefreeTrack)
        val after = repository.nowPlaying.value
        assertEquals("Carefree Highway", after?.title)
        assertNotEquals(before?.play, after?.play)
        assertEquals(
            "Carefree Highway",
            repository.trackChanges.value
                ?.nowPlaying
                ?.title,
        )
        assertNull(repository.ad.value)
    }

    @Test
    fun `the pipeline's reports never name the ad`() {
        val spotify = spotify()
        val reports = mutableListOf<TrackChange?>()
        scope.launch { repository.trackChanges.collect { reports += it } }
        spotify.settled(ad)
        spotify.settled(carefreeTrack)
        assertEquals(listOf("Sundown", null, "Carefree Highway"), reports.map { it?.nowPlaying?.title })
    }

    @Test
    fun `no change is recorded into or out of an ad, so skip detection learns nothing from it`() {
        val spotify = spotify()
        val changes = mutableListOf<SessionChange?>()
        scope.launch { repository.lastChange.collect { changes += it } }
        spotify.settled(ad)
        spotify.settled(carefreeTrack)
        assertEquals(listOf<SessionChange?>(null), changes)
        // The track after the ad carries no judged cause: the verdict when detection cannot tell.
        assertEquals(ChangeCause.NATURAL_END, repository.trackChanges.value?.cause)
    }

    @Test
    fun `an ad told only by its queue item is published as no track`() {
        val spotify = spotify()
        spotify.sendQueue(
            listOf(
                queueItem(1, "Sundown", mediaId = "spotify:track:1"),
                queueItem(2, "tetesept", mediaId = "spotify:ad:1"),
            ),
            "Play Queue",
        )
        spotify.send(state(queueItem = 2))
        spotify.settled(metadata(title = "tetesept", artist = "Advertisement • 2 of 3"))
        assertNull(repository.nowPlaying.value)
        assertEquals(SPOTIFY, repository.ad.value?.player)
    }

    @Test
    fun `an ad told by a queue that comes after its metadata and state have settled is still told`() {
        val spotify = spotify()
        spotify.send(state(queueItem = 2))
        spotify.settled(metadata(title = "tetesept", artist = "Advertisement • 2 of 3"))
        assertEquals("not told yet: no queue", "tetesept", repository.nowPlaying.value?.title)
        spotify.sendQueue(adQueue, "Play Queue")
        settle()
        assertNull(repository.nowPlaying.value)
        assertEquals(SPOTIFY, repository.ad.value?.player)
    }

    @Test
    fun `the track after an ad shows while the active item still names the ad`() {
        val spotify = spotify()
        spotify.sendQueue(adQueue, "Play Queue")
        spotify.send(state(queueItem = 2))
        spotify.settled(metadata(title = "tetesept", artist = "Advertisement • 2 of 3"))
        assertEquals(SPOTIFY, repository.ad.value?.player)
        // Its own media id says it is music, whatever the lagging item says.
        spotify.settled(carefreeTrack)
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        assertNull(repository.ad.value)
    }

    @Test
    fun `an ad next in the queue is not prepared`() {
        val spotify = spotify()
        val next = mutableListOf<String?>()
        scope.launch { combine(repository.nowPlaying, repository.queue, NextInQueue::of).collect { next += it?.title } }
        spotify.sendQueue(
            listOf(
                queueItem(1, "Sundown", mediaId = "spotify:track:1"),
                queueItem(2, "tetesept", mediaId = "spotify:ad:1"),
            ),
            "Play Queue",
        )
        settle()
        spotify.settled(ad)
        assertTrue("never the ad: $next", next.none { it == "tetesept" })
        assertNull(next.last())
    }
}
