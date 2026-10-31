package app.sleevesight.app.ui.home

import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a source's row in *Sources*' table says it found (#768): a list built from the scopes of
 * its own lines, the track first, joined with commas and a last "and"; web search's pages; the
 * outcome alone when its lines are not shown; and every other status as it was. In the app's own
 * words, from its resources.
 */
@RunWith(RobolectricTestRunner::class)
class SourceFindingTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val words = Words { id, args -> app.getString(id, *args) }

    private fun line(
        source: SourceId,
        scope: Scope,
    ): EvidenceDraft = draft(source, "A line about the ${scope.name.lowercase()} (${source.tag}).", scope = scope)

    private fun lines(vararg drafts: EvidenceDraft): List<EvidenceLine> =
        EvidenceSet.of(drafts.toList(), JvmHasher).lines

    private fun chip(
        source: SourceId = SourceId.MUSICBRAINZ,
        outcome: Outcome = Outcome.SongArticle,
        cached: Boolean = false,
        filters: Set<SourceId> = setOf(source),
    ) = SourceChip(source, ChipStatus.Done(outcome, cached), filters)

    /** What [chip]'s row says, given [lines], with grounding [details] or not. */
    private fun said(
        chip: SourceChip,
        lines: List<EvidenceLine>,
        details: Boolean = false,
    ) = statusText(chip.status, details, finding(chip, lines), words)

    /** MusicBrainz's row, with a line of each of [scopes]. */
    private fun found(vararg scopes: Scope) =
        said(chip(), lines(*scopes.map { line(SourceId.MUSICBRAINZ, it) }.toTypedArray()))

    @Test
    fun eachScopeAloneIsFoundAlone() {
        assertEquals("Found the track", found(Scope.SONG))
        assertEquals("Found the original song", found(Scope.ORIGINAL_SONG))
        assertEquals("Found the artist", found(Scope.ARTIST))
    }

    @Test
    fun twoScopesAreJoinedWithAnd() {
        assertEquals("Found the track and the artist", found(Scope.SONG, Scope.ARTIST))
        assertEquals("Found the track and the original song", found(Scope.SONG, Scope.ORIGINAL_SONG))
        assertEquals("Found the original song and the artist", found(Scope.ORIGINAL_SONG, Scope.ARTIST))
    }

    @Test
    fun allThreeAreJoinedWithACommaAndALastAndWithNoSerialComma() =
        assertEquals(
            "Found the track, the original song and the artist",
            found(Scope.SONG, Scope.ORIGINAL_SONG, Scope.ARTIST),
        )

    @Test
    fun theListIsInItsOwnOrderWhateverTheLinesAndEachScopeOnce() {
        assertEquals(
            "Found the track, the original song and the artist",
            found(Scope.ARTIST, Scope.ORIGINAL_SONG, Scope.ARTIST, Scope.SONG, Scope.SONG),
        )
        assertEquals(listOf(Scope.SONG, Scope.ORIGINAL_SONG, Scope.ARTIST), FINDING_ORDER)
    }

    @Test
    fun theListComesFromTheLinesNotTheOutcome() {
        // The outcome says the artist; the lines say the track too, and the artist is not "only".
        val lines = lines(line(SourceId.LASTFM, Scope.SONG), line(SourceId.LASTFM, Scope.ARTIST))
        assertEquals("Found the track and the artist", said(chip(SourceId.LASTFM, Outcome.ArtistArticle), lines))
    }

    @Test
    fun onlyTheSourcesOwnLinesCountTheOnesItFiltersIncluded() {
        val lines =
            lines(
                line(SourceId.MUSICBRAINZ, Scope.ARTIST),
                line(SourceId.WIKIPEDIA, Scope.SONG),
                line(SourceId.WIKIDATA, Scope.ORIGINAL_SONG),
            )
        assertEquals("Found the artist", said(chip(), lines))
        // Wikipedia's chip shows and hides Wikidata's lines: its row counts them.
        val wikipedia = chip(SourceId.WIKIPEDIA, filters = setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA))
        assertEquals("Found the track and the original song", said(wikipedia, lines))
    }

    @Test
    fun webSearchFoundPagesAboutTheTrack() {
        val web = chip(SourceId.WEB)
        assertEquals("Found pages about the track", said(web, lines(line(SourceId.WEB, Scope.WEB))))
        assertEquals(Finding.Pages, finding(web, lines(line(SourceId.WEB, Scope.WEB))))
    }

    @Test
    fun withNoLinesOfThoseScopesTheOutcomeAloneInTheSameWords() {
        val none = emptyList<EvidenceLine>()
        assertEquals("Found the track", said(chip(outcome = Outcome.SongArticle), none))
        assertEquals("Found the artist", said(chip(outcome = Outcome.ArtistArticle), none))
        assertEquals("Found the original song", said(chip(outcome = Outcome.OriginalSong), none))
        // Lines of other sources, or of scopes not in the list (the release, the web), are none.
        val others = lines(line(SourceId.WIKIPEDIA, Scope.ARTIST), line(SourceId.MUSICBRAINZ, Scope.RELEASE))
        assertEquals("Found the track", said(chip(), others))
        // Web search with none of its lines shown: still its pages, as it finds nothing else.
        assertEquals("Found pages about the track", said(chip(SourceId.WEB), others))
    }

    @Test
    fun keptGoesAfterTheListWithGroundingDetailsOnly() {
        val lines = lines(line(SourceId.MUSICBRAINZ, Scope.SONG), line(SourceId.MUSICBRAINZ, Scope.ARTIST))
        val kept = chip(cached = true)
        assertEquals("Found the track and the artist (kept)", said(kept, lines, details = true))
        assertEquals("Found the track and the artist", said(kept, lines))
        assertEquals(
            "Found pages about the track (kept)",
            said(chip(SourceId.WEB, cached = true), lines(line(SourceId.WEB, Scope.WEB)), details = true),
        )
    }

    @Test
    fun everyOtherStatusStaysAsItWas() {
        val lines = lines(line(SourceId.MUSICBRAINZ, Scope.SONG))

        fun status(
            status: ChipStatus,
            details: Boolean = false,
        ) = said(SourceChip(SourceId.MUSICBRAINZ, status, setOf(SourceId.MUSICBRAINZ)), lines, details)
        assertEquals("Looking…", status(ChipStatus.Pending))
        assertEquals("Failed", status(ChipStatus.Failed))
        assertEquals("Needs your key, in Settings", status(ChipStatus.NeedsKey))
        assertEquals("Gave the release", status(ChipStatus.Release))
        assertEquals("Allowed in Settings", status(ChipStatus.ModelAllowed))
        assertEquals("Nothing found", status(ChipStatus.Done(Outcome.None, false)))
        assertEquals("Could not be reached", status(ChipStatus.Done(Outcome.Unreachable, false)))
        assertEquals("Off in Settings", status(ChipStatus.Done(Outcome.SourceOff, false)))
        assertEquals("Nothing found", status(ChipStatus.Done(Outcome.Rejected("a film"), false)))
        assertEquals("Match rejected", status(ChipStatus.Done(Outcome.Rejected("a film"), false), details = true))
        assertEquals("Nothing found (kept)", status(ChipStatus.Done(Outcome.None, true), details = true))
        for (other in listOf(ChipStatus.Pending, ChipStatus.Release, ChipStatus.Done(Outcome.None, false))) {
            assertNull(finding(SourceChip(SourceId.MUSICBRAINZ, other, setOf(SourceId.MUSICBRAINZ)), lines))
        }
    }

    @Test
    fun aListOfOneIsTheItemAndOfNoneNothing() {
        assertEquals("a", listText(listOf("a"), words))
        assertEquals("", listText(emptyList(), words))
        assertEquals("a, b, c and d", listText(listOf("a", "b", "c", "d"), words))
    }
}
