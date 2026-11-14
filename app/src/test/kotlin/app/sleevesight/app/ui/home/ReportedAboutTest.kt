package app.sleevesight.app.ui.home

import app.sleevesight.app.report.ReportReason
import app.sleevesight.app.report.insightReport
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a report of an item's insight says it is of, as the row it was opened from names it (#1276). */
class ReportedAboutTest {
    private val nessunDorma =
        sundown.copy(title = "Nessun Dorma", artists = "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta")

    // The track as the report captured it: with the roles telling the composer apart, or none.
    private fun track(roles: ArtistRoles?): TrackState =
        HomeFixtures
            .track(nessunDorma, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(resolution = roles?.let { Resolution(recordingMbid = "r", roles = it) })

    private val split = track(ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti", "Zubin Mehta")))

    // The line the report's body names the insight with.
    private fun reportLine(
        item: InsightItem,
        track: TrackState,
    ): String {
        val about = reportedAbout(item, track)
        val body = insightReport(track, written("A line [W1]."), ReportReason.WRONG, "", about).body
        return body.lines().single { it.startsWith("Insight: ") }
    }

    @Test
    fun aPerformerReportOnASplitTrackSaysThePerformers() {
        assertEquals("Insight: the performer's", reportLine(InsightItem.ARTIST, split))
        assertEquals("Insight: the composer's", reportLine(InsightItem.COMPOSER, split))
    }

    @Test
    fun anArtistReportOnAnUnsplitTrackSaysTheArtists() {
        val unsplit = track(null)
        assertEquals("Insight: the artist's", reportLine(InsightItem.ARTIST, unsplit))
        assertEquals("Insight: the song's", reportLine(InsightItem.SONG, unsplit))
        assertEquals("Insight: the album's", reportLine(InsightItem.ALBUM, unsplit))
    }

    @Test
    fun aChipsReportStaysTheArtists() {
        assertEquals("Insight: the artist's", reportLine(InsightItem.named("Zubin Mehta"), split))
    }

    @Test
    fun aReportOfNoItemNamesNone() {
        val body = insightReport(split, written("A line [W1]."), ReportReason.WRONG, "").body
        assertTrue(body.lines().none { it.startsWith("Insight: ") })
    }
}
