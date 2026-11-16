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
import app.sleevesight.core.sources.WorkLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a report of an item's insight says it is of, as the row it was opened from names it (#1276). */
class ReportedAboutTest {
    private val nessunDorma =
        sundown.copy(title = "Nessun Dorma", artists = "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta")

    private val roles = ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti", "Zubin Mehta"))

    // The track as the report captured it: with the roles telling the composer apart, or none, and
    // what the work's MusicBrainz credits say of its words.
    private fun track(
        roles: ArtistRoles?,
        lyrics: WorkLyrics = WorkLyrics.NONE,
    ): TrackState =
        HomeFixtures
            .track(nessunDorma, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(resolution = roles?.let { Resolution(recordingMbid = "r", roles = it, lyrics = lyrics) })

    private val split = track(roles)

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
    fun aComposerRowReportSaysWhatTheRowIsCalled() {
        // Words by others (an aria's librettists, #1274): Authors.
        val libretto = track(roles, WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni")))
        assertEquals("Insight: the authors'", reportLine(InsightItem.COMPOSER, libretto))
        assertEquals("Insight: the performer's", reportLine(InsightItem.ARTIST, libretto))
        // A songwriter's own words on a work no classical one: Author.
        val own = track(roles.copy(classical = false), WorkLyrics(lyricists = listOf("Giacomo Puccini")))
        assertEquals("Insight: the author's", reportLine(InsightItem.COMPOSER, own))
    }

    @Test
    fun aSongReportOnAClassicalTrackSaysTheMusics() {
        // #1331: the song's row is Music on a classical work, Composer or Authors alike.
        val classical = roles.copy(classical = true)
        assertEquals("Insight: the music's", reportLine(InsightItem.SONG, track(classical)))
        val libretto = WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni"))
        assertEquals("Insight: the music's", reportLine(InsightItem.SONG, track(classical, libretto)))
        // A songwriter's own words on a work no classical one (Author), and no classical work: Song.
        val own = track(roles, WorkLyrics(lyricists = listOf("Giacomo Puccini")))
        assertEquals("Insight: the song's", reportLine(InsightItem.SONG, own))
        assertEquals("Insight: the song's", reportLine(InsightItem.SONG, split))
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
