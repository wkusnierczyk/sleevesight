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

    @Test
    fun `an item without a title is not prepared`() {
        val queue = SessionQueue(null, listOf(QueueEntry(1, "A", "X", "m1"), QueueEntry(2, " ", "Y", "m2")))
        assertNull(NextInQueue.of(playing(mediaId = "m1"), queue))
    }
}
