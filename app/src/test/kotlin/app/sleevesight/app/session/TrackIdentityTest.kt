package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TrackIdentityTest {
    private val sundown = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = SPOTIFY)

    @Test
    fun theMediaIdDecidesWhenBothSendOne() {
        val a = sundown.copy(mediaId = "spotify:track:1", duration = 1.seconds)
        assertTrue(TrackIdentity.same(a, a.copy(duration = 223.seconds, artUri = "content://art/2")))
        assertFalse(TrackIdentity.same(a, a.copy(mediaId = "spotify:track:2")))
        // Another recording of the same name is another track.
        assertFalse(TrackIdentity.same(a, a.copy(mediaId = "spotify:track:3", queueItemId = 5)))
    }

    @Test
    fun withoutMediaIdsTheActiveQueueItemDecides() {
        // YouTube Music re-sends the playing track with a new length; the queue item is its identity.
        val a = sundown.copy(player = YOUTUBE_MUSIC, queueItemId = 4, duration = 106.seconds)
        assertTrue(TrackIdentity.same(a, a.copy(duration = 278.8.seconds)))
        // The same song queued twice, as separate items, is a new track.
        assertFalse(TrackIdentity.same(a, a.copy(queueItemId = 5)))
    }

    @Test
    fun whenEitherSideHasNoQueueItemTitleAndArtistsDecide() {
        val a = sundown.copy(player = YOUTUBE_MUSIC, queueItemId = 4)
        assertTrue(TrackIdentity.same(a, a.copy(queueItemId = null)))
        assertFalse(TrackIdentity.same(a, a.copy(queueItemId = null, title = "Carefree Highway")))
        assertFalse(TrackIdentity.same(sundown, sundown.copy(artists = "Gordon Lightfoot, Someone Else")))
        assertTrue(TrackIdentity.same(sundown, sundown.copy(album = "Sundown", duration = 223.seconds)))
    }

    @Test
    fun aMediaIdOnOneSideOnlyFallsThrough() {
        assertTrue(TrackIdentity.same(sundown.copy(mediaId = "spotify:track:1"), sundown))
        assertFalse(
            TrackIdentity.same(
                sundown.copy(mediaId = "spotify:track:1", queueItemId = 1),
                sundown.copy(queueItemId = 2),
            ),
        )
    }

    @Test
    fun tracksOfDifferentPlayersDiffer() {
        assertFalse(TrackIdentity.same(sundown, sundown.copy(player = YOUTUBE_MUSIC)))
    }
}
