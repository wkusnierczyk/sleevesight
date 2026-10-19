package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertEquals
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
    fun theQueueItemDecidesNothing() {
        val a = sundown.copy(player = YOUTUBE_MUSIC, queueItemId = 4, duration = 106.seconds)
        val rows =
            listOf(
                // The same song queued twice, as separate items: the same track.
                Triple("another item", a.copy(queueItemId = 5), true),
                Triple("no item", a.copy(queueItemId = null), true),
                // YouTube Music re-sends the playing track with a new length.
                Triple("a new length", a.copy(duration = 278.8.seconds), true),
                Triple("another title, the same item", a.copy(title = "Carefree Highway"), false),
                Triple("another title and item", a.copy(title = "Carefree Highway", queueItemId = 5), false),
                Triple("other artists, the same item", a.copy(artists = "Someone Else"), false),
            )
        for ((name, b, same) in rows) assertEquals(name, same, TrackIdentity.same(a, b))
    }

    @Test
    fun withoutMediaIdsTitleAndArtistsDecide() {
        assertFalse(TrackIdentity.same(sundown, sundown.copy(artists = "Gordon Lightfoot, Someone Else")))
        assertTrue(TrackIdentity.same(sundown, sundown.copy(album = "Sundown", duration = 223.seconds)))
    }

    @Test
    fun aMediaIdOnOneSideOnlyFallsThrough() {
        assertTrue(TrackIdentity.same(sundown.copy(mediaId = "spotify:track:1"), sundown))
        assertTrue(
            TrackIdentity.same(
                sundown.copy(mediaId = "spotify:track:1", queueItemId = 1),
                sundown.copy(queueItemId = 2),
            ),
        )
        assertFalse(TrackIdentity.same(sundown.copy(mediaId = "spotify:track:1"), sundown.copy(title = "Other")))
    }

    @Test
    fun aReportWithoutArtistsMatchesAnyArtists() {
        // YouTube Music at the start of a session: the title first, its artists a moment later.
        assertTrue(TrackIdentity.same(sundown.copy(artists = ""), sundown))
        assertTrue(TrackIdentity.same(sundown, sundown.copy(artists = "")))
        assertFalse(TrackIdentity.same(sundown.copy(artists = ""), sundown.copy(title = "Carefree Highway")))
    }

    @Test
    fun tracksOfDifferentPlayersDiffer() {
        assertFalse(TrackIdentity.same(sundown, sundown.copy(player = YOUTUBE_MUSIC)))
    }
}
