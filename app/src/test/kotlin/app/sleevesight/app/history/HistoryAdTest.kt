package app.sleevesight.app.history

import app.sleevesight.app.session.Sessions.advertised
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An ad between two tracks (#880), through the real session layer and recorder: the history keeps
 * the tracks and not the ad, and records no track change into or out of it, so skip detection never
 * learns from one. The ad is synthetic (`AdDetectionTest`).
 */
@RunWith(RobolectricTestRunner::class)
internal class HistoryAdTest : HistoryRecorderFixture() {
    @Test
    fun `an ad is kept in no row and no track change`() {
        val spotify = spotify(metadata(mediaId = "spotify:track:1", durationMs = 225_000))
        at(60_000)
        spotify.report(advertised(metadata(title = "tetesept", artist = "Advertisement • 2 of 3", durationMs = 28_000)))
        at(90_000)
        spotify.report(metadata(title = "Carefree Highway", mediaId = "spotify:track:2", durationMs = 230_000))
        settleAll()

        assertEquals(listOf("Sundown", "Carefree Highway"), allRows().map { it.track.title })
        val changes = history.changes(limit = 10).changes
        assertEquals(emptyList<String>(), changes.map { it.to.title })
    }

    @Test
    fun `an ad told late leaves no row and no track change behind`() {
        // Told only by its queue, which comes after its metadata and state have settled: recorded
        // as a track for a moment, then taken back.
        val spotify = spotify(metadata(mediaId = "spotify:track:1", durationMs = 225_000))
        at(60_000)
        spotify.send(state(queueItem = 2))
        spotify.report(metadata(title = "tetesept", artist = "Advertisement • 2 of 3", durationMs = 28_000))
        assertEquals("recorded first", listOf("Sundown", "tetesept"), allRows().map { it.track.title })
        at(61_000)
        spotify.sendQueue(
            listOf(
                queueItem(1, "Sundown", mediaId = "spotify:track:1"),
                queueItem(2, "tetesept", mediaId = "spotify:ad:1"),
            ),
            "Play Queue",
        )
        settleAll()
        at(90_000)
        spotify.report(metadata(title = "Carefree Highway", mediaId = "spotify:track:2", durationMs = 230_000))
        settleAll()

        assertEquals(listOf("Sundown", "Carefree Highway"), allRows().map { it.track.title })
        assertEquals(emptyList<String>(), history.changes(limit = 10).changes.map { it.to.title })
    }
}
