package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The genres past each row's cap (#1281): kept, in the order they weigh, apart from the row's five
 * ([GenreRow.extras]), so that what reads the rows for a prompt keeps the five; shown as chips on a
 * tap, each genre once and none a chip already ([TrackGenres.extraChips]); weak only as #996 leaves
 * Instrumental weak.
 */
class GenreExtrasTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun track(vararg tags: String) =
        vocabulary.labelsFor(tags.toList(), level = GenreLevel.TRACK, capped = false)

    private fun artist(vararg tags: String) =
        vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = "A", capped = false)

    private fun genres(
        lastFm: List<GenreLabel> = emptyList(),
        musicBrainz: List<GenreLabel> = emptyList(),
        singing: Singing = Singing.NONE,
    ) = combineGenres(mapOf(LabelSource.LASTFM to lastFm, LabelSource.MUSICBRAINZ to musicBrainz), listOf("A"), singing)

    private val eight = arrayOf("rock", "pop", "jazz", "blues", "folk", "soul", "funk", "punk")

    private fun TrackGenres.names(labels: List<TrackLabel>) = labels.map { it.genre.label }

    @Test
    fun `a row keeps its five and the labels past them as extras, in the order they weigh`() {
        val row = genres(artist(*eight)).rows.single()
        assertEquals(listOf("Rock", "Pop", "Jazz", "Blues", "Folk"), row.labels.map { it.genre.label })
        assertEquals(listOf("Soul", "Funk", "Punk"), row.extras.map { it.genre.label })
    }

    @Test
    fun `a row with exactly five has no extras, and no chip past the cap`() {
        val genres = genres(artist(*eight.take(5).toTypedArray()))
        assertTrue(
            genres.rows
                .single()
                .extras
                .isEmpty(),
        )
        assertTrue(genres.extraChips().isEmpty())
    }

    @Test
    fun `the rows' labels, what prompts read, are the five alone`() {
        val genres = genres(artist(*eight))
        assertEquals(listOf("Rock", "Pop", "Jazz", "Blues", "Folk"), genres.names(genres.all))
        assertEquals(genres.all, genres.everyLabel)
    }

    @Test
    fun `the chips past the cap are each genre once, none a chip already, alphabetical`() {
        val lastFm = track("rock", "pop", "jazz", "blues", "folk", "soul", "funk") + artist(*eight)
        val genres = genres(lastFm)
        // Soul and Funk are past both rows' five; Punk past the artist's: each once.
        assertEquals(listOf("Funk", "Punk", "Soul"), genres.extraChips().map { it.label.genre.label })
        // Placed on the first row whose extras have it.
        assertEquals(
            GenreLevel.TRACK,
            genres
                .extraChips()
                .first()
                .row.level,
        )
        assertEquals(listOf("Funk", "Punk", "Soul"), genres.names(genres.extraLabels).sorted())
        assertEquals(genres.everyLabel + genres.extraLabels, genres.openable)
    }

    @Test
    fun `a genre past one row's five but among another's is a chip, not an extra`() {
        val genres = genres(track("rock", "pop", "jazz", "blues", "folk", "soul") + artist("soul"))
        assertTrue(genres.extraChips().isEmpty())
        assertTrue(genres.chips().any { it.label.genre.label == "Soul" })
    }

    @Test
    fun `an extra with few votes is not weak, but an extra Instrumental on a sung track is, with why`() {
        val sung = Singing(vocalists = listOf("Singer"))
        val genres = genres(track("rock", "pop", "jazz", "blues", "folk", "soul", "instrumental"), singing = sung)
        val extras = genres.extraChips().associate { it.label.genre.label to it.weak }
        assertEquals(mapOf("Instrumental" to true, "Soul" to false), extras)
        assertIs<WeakEvidence.Vocalist>(genres.weakEvidence(TrackGenres.INSTRUMENTAL))
        assertNull(genres.weakEvidence("soul"))
    }

    @Test
    fun `lyrics found drop Instrumental from the extras too`() {
        val genres = genres(track("rock", "pop", "jazz", "blues", "folk", "instrumental", "soul"))
        assertEquals(
            listOf("Instrumental", "Soul"),
            genres.rows
                .single()
                .extras
                .map { it.genre.label },
        )
        assertEquals(
            listOf("Soul"),
            genres
                .withLyricsFound()
                .rows
                .single()
                .extras
                .map { it.genre.label },
        )
    }

    @Test
    fun `without the sources' labels, Instrumental dropped from the five leaves its room to the next extra`() {
        val row = genres(track("instrumental", "rock", "pop", "jazz", "blues", "folk")).rows.single()
        val dropped = TrackGenres(listOf(row)).withLyricsFound().rows.single()
        assertEquals(listOf("Rock", "Pop", "Jazz", "Blues", "Folk"), dropped.labels.map { it.genre.label })
        assertTrue(dropped.extras.isEmpty())
    }
}
