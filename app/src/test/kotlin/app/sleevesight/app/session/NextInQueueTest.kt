package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The next item in the player's queue (D13), as Phase 0 measured the queues. */
class NextInQueueTest {
    private val spotifyQueue =
        SessionQueue(
            "Play Queue",
            listOf(
                QueueEntry(1, "Kalabancoro", "Ginton, Richard Bona, Salif Keita", "spotify:track:1"),
                QueueEntry(2, "Yamore", "Salif Keita, Cesária Evora", "spotify:track:2"),
                QueueEntry(3, "Madan", "Jonas Blue, Salif Keita", "spotify:track:3"),
            ),
        )

    private fun playing(
        mediaId: String? = null,
        item: Long? = null,
    ) = NowPlaying(
        title = "Kalabancoro",
        artists = "Ginton",
        album = "Kalabancoro",
        player = "com.spotify.music",
        mediaId = mediaId,
        queueItemId = item,
    )

    @Test
    fun `the item after the playing track's media id, with no album or length`() {
        assertEquals(
            NowPlaying(
                title = "Yamore",
                artists = "Salif Keita, Cesária Evora",
                player = "com.spotify.music",
                mediaId = "spotify:track:2",
                queueItemId = 2,
            ),
            NextInQueue.of(playing(mediaId = "spotify:track:1", item = 1), spotifyQueue),
        )
    }

    @Test
    fun `the media id goes first, since the active item can lag behind a new queue`() {
        assertEquals("Madan", NextInQueue.of(playing(mediaId = "spotify:track:2", item = 1), spotifyQueue)?.title)
    }

    @Test
    fun `without a media id, the item after the active queue item, as for YouTube Music`() {
        assertEquals("Madan", NextInQueue.of(playing(item = 2), spotifyQueue)?.title)
    }

    @Test
    fun `no next item for a track not in the queue, the last one, or no queue`() {
        assertNull(NextInQueue.of(playing(mediaId = "spotify:track:9", item = 9), spotifyQueue))
        assertNull(NextInQueue.of(playing(), spotifyQueue))
        assertNull(NextInQueue.of(playing(mediaId = "spotify:track:3"), spotifyQueue))
        assertNull(NextInQueue.of(playing(mediaId = "spotify:track:1"), null))
        assertNull(NextInQueue.of(null, spotifyQueue))
    }

    // A media id queued twice: A, B, A, C.
    private val repeated =
        SessionQueue(
            null,
            listOf(
                QueueEntry(1, "A", "X", "a"),
                QueueEntry(2, "B", "X", "b"),
                QueueEntry(3, "A", "X", "a"),
                QueueEntry(4, "C", "X", "c"),
            ),
        )

    @Test
    fun `a media id queued twice is told apart by the active queue item`() {
        assertEquals("C", NextInQueue.of(playing(mediaId = "a", item = 3), repeated)?.title)
        assertEquals("B", NextInQueue.of(playing(mediaId = "a", item = 1), repeated)?.title)
    }

    @Test
    fun `a media id queued twice with no active item, or one not in the queue, is ambiguous`() {
        assertNull(NextInQueue.of(playing(mediaId = "a"), repeated))
        assertNull(NextInQueue.of(playing(mediaId = "a", item = 9), repeated))
    }

    @Test
    fun `a media id queued twice, the active item lagging on the track before, is the first after it`() {
        // The active item still names B, at 2: the playing A is the one at 3, so C is next.
        assertEquals("C", NextInQueue.of(playing(mediaId = "a", item = 2), repeated)?.title)
    }

    @Test
    fun `a media id queued twice, the active item leading onto the entry after, picks the later match`() {
        // The accepted cost, not the desired answer: the metadata is still on the first A while
        // the active item is already on B, at 2, so the A at 3 is taken and C prepared for a
        // moment, where B is next. The report is the lagging case's, read the other way:
        // `NextInQueue` cannot tell them apart, and takes the lag, the only one seen in media-id
        // players. The next report replaces the preparation, which makes no model call.
        assertEquals("C", NextInQueue.of(playing(mediaId = "a", item = 2), repeated)?.title)
    }

    @Test
    fun `a media id queued twice, none of it after the active item, is ambiguous`() {
        // The active item names C, the last: no A comes after it.
        assertNull(NextInQueue.of(playing(mediaId = "a", item = 4), repeated))
    }

    @Test
    fun `a media id found once goes before an active item that names another track`() {
        // B is found once, at 2; the active item, 3, names the second A.
        assertEquals("A", NextInQueue.of(playing(mediaId = "b", item = 3), repeated)?.title)
    }

    @Test
    fun `an item without a title is not prepared`() {
        val queue = SessionQueue(null, listOf(QueueEntry(1, "A", "X", "m1"), QueueEntry(2, " ", "Y", "m2")))
        assertNull(NextInQueue.of(playing(mediaId = "m1"), queue))
    }
}
