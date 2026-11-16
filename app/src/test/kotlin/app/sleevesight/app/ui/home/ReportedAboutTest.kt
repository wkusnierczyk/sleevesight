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

    // The track as replayed from a result kept from an earlier play: no resolution, the roles and the
    // work's credits as MusicBrainz's kept result holds them.
    private fun kept(
        roles: ArtistRoles,
        lyrics: WorkLyrics?,
    ): TrackState {
        val done = done()
        val result = done.result.copy(roles = roles, lyrics = lyrics)
        return HomeFixtures.track(nessunDorma, sources = mapOf(SourceId.MUSICBRAINZ to done.copy(result = result)))
    }

    @Test
    fun aSongReportBesideAComposerRowOrAClassicalAuthorsRowSaysTheMusics() {
        // #1331: the song's row is Music beside a Composer row, and beside Authors on a classical work.
        assertEquals("Insight: the composer's", reportLine(InsightItem.COMPOSER, split))
        assertEquals("Insight: the music's", reportLine(InsightItem.SONG, split))
        val classical = roles.copy(classical = true)
        assertEquals("Insight: the music's", reportLine(InsightItem.SONG, track(classical)))
        val libretto = WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni"))
        assertEquals("Insight: the authors'", reportLine(InsightItem.COMPOSER, track(classical, libretto)))
        assertEquals("Insight: the music's", reportLine(InsightItem.SONG, track(classical, libretto)))
        // Authors on a work not classical, and Author (a songwriter's own words): Song.
        assertEquals("Insight: the song's", reportLine(InsightItem.SONG, track(roles, libretto)))
        val own = track(roles, WorkLyrics(lyricists = listOf("Giacomo Puccini")))
        assertEquals("Insight: the author's", reportLine(InsightItem.COMPOSER, own))
        assertEquals("Insight: the song's", reportLine(InsightItem.SONG, own))
    }

    @Test
    fun aSongReportOnAResultKeptFromAnEarlierPlayFollowsItsComposerRow() {
        // Roles kept with no classical form: Composer, so Music; Authors on them, Song.
        assertEquals("Insight: the composer's", reportLine(InsightItem.COMPOSER, kept(roles, null)))
        assertEquals("Insight: the music's", reportLine(InsightItem.SONG, kept(roles, null)))
        val libretto = WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni"))
        assertEquals("Insight: the song's", reportLine(InsightItem.SONG, kept(roles, libretto)))
        assertEquals("Insight: the music's", reportLine(InsightItem.SONG, kept(roles.copy(classical = true), libretto)))
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
