package app.sleevesight.core.genres

import app.sleevesight.core.lyrics.LibraryLyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An Instrumental only Wikidata states (#1292): Wikidata's statements are edited by its users, as
 * LRCLIB's records are submitted by its users (#1055), so it shows weak, and wants the check of the
 * track's vocals; with LRCLIB's mark backing it too and nothing else, weak still, the check settled
 * by the mark. Another source's tag of it, on any row, MusicBrainz's mark, or the check finding it
 * instrumental make it firm.
 */
class WikidataInstrumentalTest {
    private fun label(
        tag: String,
        level: GenreLevel,
        vararg sources: LabelSource,
    ) = TrackLabel(
        GenreLabel(tag, tag.replaceFirstChar(Char::uppercase), tag, level = level, votes = null),
        sources.map { LabelCredit(it, null) },
    )

    private fun genres(
        vararg rows: GenreRow,
        singing: Singing = Singing.NONE,
    ) = TrackGenres(rows.toList(), credited = listOf(ARTIST), singing = singing)

    private fun track(vararg labels: TrackLabel) = GenreRow(GenreLevel.TRACK, null, labels.toList())

    private fun stated(singing: Singing = Singing.NONE) =
        genres(track(label(INSTRUMENTAL, GenreLevel.TRACK, LabelSource.WIKIDATA)), singing = singing)

    private fun TrackGenres.chip() = chips().single { it.label.genre.identity == INSTRUMENTAL }

    @Test
    fun `an Instrumental only Wikidata states is weak for its statement, and wants the check of the vocals`() {
        val genres = stated()
        assertTrue(genres.chip().weak)
        assertEquals(WeakEvidence.WikidataStatement, genres.weakEvidence(INSTRUMENTAL))
        assertTrue(genres.wantsVocalsCheck())
    }

    @Test
    fun `with LRCLIB's mark and Wikidata alone it is weak for both, and the mark settles the check`() {
        val genres = stated(Singing(library = LibraryLyrics.INSTRUMENTAL))
        assertTrue(genres.chip().weak)
        assertEquals(WeakEvidence.LibraryAndWikidata, genres.weakEvidence(INSTRUMENTAL))
        assertFalse(genres.wantsVocalsCheck())
    }

    @Test
    fun `LRCLIB's mark lifting a sign of vocals from Wikidata's Instrumental names both`() {
        val genres = stated(Singing(vocalists = listOf("Someone"), library = LibraryLyrics.INSTRUMENTAL))
        assertEquals(WeakEvidence.LibraryAndWikidata, genres.weakEvidence(INSTRUMENTAL))
    }

    @Test
    fun `a sign of vocals still comes first`() {
        val genres = stated(Singing(vocalists = listOf("Someone")))
        assertEquals(WeakEvidence.Vocalist("Someone"), genres.weakEvidence(INSTRUMENTAL))
        assertTrue(genres.wantsVocalsCheck())
    }

    @Test
    fun `with another source's tag beside Wikidata's statement it stays firm`() {
        for (other in listOf(LabelSource.MUSICBRAINZ, LabelSource.LASTFM)) {
            val both = genres(track(label(INSTRUMENTAL, GenreLevel.TRACK, LabelSource.WIKIDATA, other)))
            // MusicBrainz's tag with no votes would be weak for its votes; Wikidata's statement lifts that (#1098).
            assertFalse(both.chip().weak, "$other")
            assertNull(both.weakEvidence(INSTRUMENTAL), "$other")
        }
    }

    @Test
    fun `with another source's tag of it on another row it stays firm`() {
        val tagged = label(INSTRUMENTAL, GenreLevel.ARTIST, LabelSource.LASTFM)
        val artist = GenreRow(GenreLevel.ARTIST, ARTIST, listOf(tagged))
        val genres = genres(track(label(INSTRUMENTAL, GenreLevel.TRACK, LabelSource.WIKIDATA)), artist)
        assertFalse(genres.chip().weak)
        assertNull(genres.weakEvidence(INSTRUMENTAL))
        assertFalse(genres.wantsVocalsCheck())
    }

    @Test
    fun `MusicBrainz's mark or the check finding it instrumental make it firm`() {
        val marked = stated(Singing(instrumental = true))
        assertFalse(marked.chip().weak)
        val checked = stated(Singing(checked = VocalsAnswer.INSTRUMENTAL))
        assertFalse(checked.chip().weak)
        assertNull(checked.weakEvidence(INSTRUMENTAL))
    }

    @Test
    fun `only Instrumental is weighed so`() {
        val genres = genres(track(label("jazz", GenreLevel.TRACK, LabelSource.WIKIDATA)))
        assertFalse(genres.chips().single().weak)
    }

    private companion object {
        const val INSTRUMENTAL = TrackGenres.INSTRUMENTAL
        const val ARTIST = "Artist"
    }
}
