package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.genres.instrumental
import app.sleevesight.core.genres.wantsVocalsCheck
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * LRCLIB's instrumental mark adding Instrumental for a song whose work credits composers alone
 * (#1024): on the track's row, credited to LRCLIB, a source's chip, not weak and not marked AI; the
 * paid check settled. Nothing added for lyrics or no match, with a vocal credit or a language of the
 * work, MusicBrainz's lyrics links, or an Instrumental on a row already, which the mark only lifts.
 */
class LibraryMarkTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val instrumental: GenreLabel? = vocabulary.instrumental()

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    // *A Bite of Sand*'s shape (#998): Jazz and Electronic, the work crediting [singing]'s writers.
    private fun bite(singing: Singing = Singing(composersOnly = true)) =
        combineGenres(mapOf(LabelSource.LASTFM to artist(ARTIST, "jazz", "electronic")), listOf(ARTIST), singing)

    private fun TrackGenres.instrumentals() = everyLabel.filter { it.genre.identity == TrackGenres.INSTRUMENTAL }

    @Test
    fun `composers alone and LRCLIB's instrumental mark add Instrumental on the track's row, credited to LRCLIB`() {
        val hinted = bite()
        assertTrue(hinted.wantsLibrary())
        val marked = hinted.withLibrary(LibraryLyrics.INSTRUMENTAL, instrumental)
        val track = marked.rows.first()
        assertEquals(GenreLevel.TRACK, track.level)
        val label = track.labels.single()
        assertEquals(TrackGenres.INSTRUMENTAL, label.genre.identity)
        assertEquals(SourceId.LRCLIB, label.markedBy)
        assertTrue(label.credits.isEmpty())
        assertNull(label.aiModel)
        assertFalse(label.aiAdded)
        // A source's chip, before the AI's, not weak for its votes (it has none) or any sign.
        val chip = marked.chips().single { it.label.genre.identity == TrackGenres.INSTRUMENTAL }
        assertFalse(chip.weak)
        assertNull(marked.weakEvidence(TrackGenres.INSTRUMENTAL))
        // The paid check settled, LRCLIB not asked again.
        assertFalse(marked.wantsVocalsCheck())
        assertFalse(marked.wantsLibrary())
        // Weighed as the run weighs it, combined with the answer, the same; applied twice, the same.
        val combined =
            combineGenres(
                mapOf(LabelSource.LASTFM to artist(ARTIST, "jazz", "electronic")),
                listOf(ARTIST),
                Singing(composersOnly = true, library = LibraryLyrics.INSTRUMENTAL),
            ).withLibraryMark(instrumental)
        assertEquals(marked, combined)
        assertSame(marked, marked.withLibraryMark(instrumental))
    }

    @Test
    fun `the AI's genre list naming it too credits the model beside LRCLIB, and marks it AI no more`() {
        val marked = bite().withLibrary(LibraryLyrics.INSTRUMENTAL, instrumental)
        val listed = vocabulary.labelsFor(listOf("instrumental", "jazz"), level = GenreLevel.TRACK)
        val label = marked.withAi(listed, MODEL).instrumentals().single()
        assertEquals(MODEL, label.aiModel)
        assertEquals(SourceId.LRCLIB, label.markedBy)
        assertFalse(label.aiAdded)
    }

    @Test
    fun `lyrics or no match from LRCLIB add nothing`() {
        assertTrue(bite().withLibrary(LibraryLyrics.LYRICS, instrumental).instrumentals().isEmpty())
        val none = bite().withLibrary(LibraryLyrics.NONE, instrumental)
        assertTrue(none.instrumentals().isEmpty())
        // No match leaves the paid check to settle it.
        assertTrue(none.wantsVocalsCheck())
    }

    @Test
    fun `with a vocal credit or a language of the work LRCLIB is not asked, and a mark kept adds nothing`() {
        for (sung in listOf(Singing(vocalists = listOf("Ana")), Singing(languages = listOf("heb")))) {
            val genres = bite(sung.copy(composersOnly = true))
            assertFalse(genres.wantsLibrary(), "$sung")
            assertTrue(genres.withLibrary(LibraryLyrics.INSTRUMENTAL, instrumental).instrumentals().isEmpty())
        }
        // Nor without composers alone, nor with MusicBrainz's lyrics links (a free sign that drops it).
        assertTrue(bite(Singing()).withLibrary(LibraryLyrics.INSTRUMENTAL, instrumental).instrumentals().isEmpty())
        val linked = bite(Singing(composersOnly = true, lyrics = true, library = LibraryLyrics.INSTRUMENTAL))
        assertTrue(linked.withLibraryMark(instrumental).instrumentals().isEmpty())
    }

    @Test
    fun `an Instrumental on a row already is not added twice, only lifted`() {
        val tagged =
            combineGenres(
                mapOf(LabelSource.LASTFM to artist(ARTIST, "instrumental", "jazz")),
                listOf(ARTIST, FEATURED),
                Singing(composersOnly = true),
            )
        // The featured artist leaves it weak until the mark lifts it.
        assertTrue(tagged.chips().single { it.label.genre.identity == TrackGenres.INSTRUMENTAL }.weak)
        val marked = tagged.withLibrary(LibraryLyrics.INSTRUMENTAL, instrumental)
        val label = marked.instrumentals().single()
        assertEquals(GenreLevel.ARTIST, label.genre.level)
        assertNull(label.markedBy)
        assertTrue(marked.rows.none { it.level == GenreLevel.TRACK })
        assertFalse(marked.chips().single { it.label.genre.identity == TrackGenres.INSTRUMENTAL }.weak)
    }

    private companion object {
        const val ARTIST = "Shay Hazan"
        const val FEATURED = "Ana"
        const val MODEL = "Claude Haiku 5.5"
    }
}
