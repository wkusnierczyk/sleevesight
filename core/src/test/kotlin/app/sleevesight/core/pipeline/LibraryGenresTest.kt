package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.WeakEvidence
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.genres.describedGenres
import app.sleevesight.core.genres.withDescribed
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * LRCLIB's answer as a sign for Instrumental (#997): lyrics drop it from every row, as MusicBrainz's
 * lyrics links do; its instrumental mark lifts each of #996's weak signs; no match leaves the genres
 * as they were; and the genres call for it only with an Instrumental nothing settles yet.
 */
class LibraryGenresTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun track(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.TRACK)

    private fun album(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ALBUM)

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    private fun genres(
        lastFm: List<GenreLabel>,
        artists: List<String> = listOf(ARTIST),
        singing: Singing = Singing.NONE,
    ) = combineGenres(mapOf(LabelSource.LASTFM to lastFm), artists, singing)

    private fun everyLevel() =
        track("instrumental", "jazz") + artist(ARTIST, "instrumental", "jazz") + album("instrumental", "jazz")

    private fun TrackGenres.shown() = chips().associate { it.label.genre.identity to it.weak }

    @Test
    fun `lyrics found by LRCLIB drop Instrumental from every row, as they are combined or after`() {
        val sung = genres(everyLevel(), singing = Singing(library = LibraryLyrics.LYRICS))
        assertEquals(listOf("jazz", "jazz", "jazz"), sung.rows.flatMap { row -> row.labels.map { it.genre.identity } })
        // Weighed after they were combined, the same.
        val later = genres(everyLevel()).withLibrary(LibraryLyrics.LYRICS)
        assertEquals(sung.rows, later.rows)
        assertEquals(LibraryLyrics.LYRICS, later.singing.library)
        // The AI's Instrumental with them: off the track's row too.
        val listed = vocabulary.labelsFor(listOf("instrumental", "blues"), level = GenreLevel.TRACK)
        val withAi = genres(artist(ARTIST, "jazz")).withAi(listed, "Model").withLibrary(LibraryLyrics.LYRICS)
        assertEquals(setOf("jazz", "blues"), withAi.all.map { it.genre.identity }.toSet())
    }

    @Test
    fun `LRCLIB's instrumental mark lifts each weak sign`() {
        val marked = LibraryLyrics.INSTRUMENTAL
        val language = Singing(languages = listOf("jpn"))
        assertEquals(
            WeakEvidence.Language("jpn", "Japanese"),
            weakOf(genres(artist(ARTIST, "instrumental"), singing = language)),
        )
        assertNull(weakOf(genres(artist(ARTIST, "instrumental"), singing = language.copy(library = marked))))
        val vocalist = Singing(vocalists = listOf(FEATURED))
        assertEquals(
            WeakEvidence.Vocalist(FEATURED),
            weakOf(genres(artist(ARTIST, "instrumental"), singing = vocalist)),
        )
        assertNull(weakOf(genres(artist(ARTIST, "instrumental"), singing = vocalist.copy(library = marked))))
        val featured = genres(artist(ARTIST, "instrumental", "funk"), artists = listOf(ARTIST, FEATURED))
        assertEquals(WeakEvidence.Featured(FEATURED), weakOf(featured))
        val lifted = featured.withLibrary(marked)
        assertNull(weakOf(lifted))
        assertEquals(mapOf("funk" to false, "instrumental" to false), lifted.shown())
        // The AI's Instrumental too.
        assertNull(genres(emptyList(), singing = vocalist.copy(library = marked)).aiSignOfVocals("instrumental"))
    }

    @Test
    fun `no match leaves the genres as they were, weak signs included`() {
        val featured = genres(artist(ARTIST, "instrumental", "funk"), artists = listOf(ARTIST, FEATURED))
        val none = featured.withLibrary(LibraryLyrics.NONE)
        assertEquals(featured.rows, none.rows)
        assertEquals(WeakEvidence.Featured(FEATURED), weakOf(none))
        assertEquals(featured, featured.withLibrary(null))
    }

    @Test
    fun `where MusicBrainz and LRCLIB disagree, the instrumental mark wins`() {
        // MusicBrainz marks it instrumental, LRCLIB has lyrics: Instrumental stays.
        val marked = genres(everyLevel(), singing = Singing(instrumental = true, library = LibraryLyrics.LYRICS))
        assertTrue(marked.all.any { it.genre.identity == TrackGenres.INSTRUMENTAL })
        // LRCLIB marks it, a lyrics page was found: Instrumental stays.
        val page = genres(everyLevel()).withLibrary(LibraryLyrics.INSTRUMENTAL).withLyricsFound()
        assertTrue(page.all.any { it.genre.identity == TrackGenres.INSTRUMENTAL })
    }

    @Test
    fun `only an Instrumental nothing settles yet calls for LRCLIB`() {
        assertTrue(genres(artist(ARTIST, "instrumental")).wantsLibrary())
        assertTrue(genres(track("instrumental")).wantsLibrary())
        assertFalse(genres(artist(ARTIST, "jazz")).wantsLibrary())
        assertFalse(TrackGenres().wantsLibrary())
        // MusicBrainz's mark, or its lyrics links (no row has it then), or an answer already in.
        assertFalse(genres(artist(ARTIST, "instrumental"), singing = Singing(instrumental = true)).wantsLibrary())
        assertFalse(genres(artist(ARTIST, "instrumental"), singing = Singing(lyrics = true)).wantsLibrary())
        assertFalse(genres(artist(ARTIST, "instrumental")).withLibrary(LibraryLyrics.NONE).wantsLibrary())
        // The AI's list adding it: called for.
        val listed = vocabulary.labelsFor(listOf("instrumental"), level = GenreLevel.TRACK)
        assertTrue(genres(artist(ARTIST, "jazz")).withAi(listed, "Model").wantsLibrary())
    }

    @Test
    fun `an Instrumental only the artist's description names takes LRCLIB's answer as any other`() {
        // *Eros*'s shape (#1000): the artist's tags, and a web line calling the act instrumental.
        val line =
            EvidenceLine(
                "S8",
                SourceId.WEB,
                Scope.ARTIST,
                "Islandman is described as an instrumental electro-acoustic band from Turkey.",
                "https://example.org/S8",
                Instant.parse("2026-11-06T00:00:00Z"),
            )
        val described = describedGenres(vocabulary, listOf(line), listOf(ARTIST))
        val eros = genres(artist(ARTIST, "ambient", "house")).withDescribed(described)
        assertIs<WeakEvidence.ArtistHint>(weakOf(eros))
        // Lyrics found by LRCLIB drop it; its instrumental mark lifts the hint; no match leaves it.
        val sung = eros.withLibrary(LibraryLyrics.LYRICS)
        assertTrue(sung.chips().none { it.label.genre.identity == TrackGenres.INSTRUMENTAL })
        assertEquals(listOf("ambient", "house"), sung.chips().map { it.label.genre.identity })
        val marked = eros.withLibrary(LibraryLyrics.INSTRUMENTAL)
        assertEquals(false, marked.shown()[TrackGenres.INSTRUMENTAL])
        assertNull(weakOf(marked))
        assertIs<WeakEvidence.ArtistHint>(weakOf(eros.withLibrary(LibraryLyrics.NONE)))
        // Not a row of the tags, but called for all the same (#998): the panel, which reads the
        // descriptions, asks LRCLIB for it; once answered, or with lyrics found, no longer.
        assertTrue(eros.wantsLibrary())
        assertFalse(eros.withLibrary(LibraryLyrics.NONE).wantsLibrary())
    }

    // Why Instrumental is weak, if it is.
    private fun weakOf(genres: TrackGenres): WeakEvidence? = genres.weakEvidence(TrackGenres.INSTRUMENTAL)

    private companion object {
        const val ARTIST = "Sababa 5"
        const val FEATURED = "Yurika Hanashima"
    }
}
