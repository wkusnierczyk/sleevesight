package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A track's genres as rows (#439): the levels, their order, rows with none left out, one level, none. */
class TrackGenresTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun track(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.TRACK)

    private fun album(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ALBUM)

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    private fun TrackGenres.shown() =
        rows.map { row -> Triple(row.level, row.artist, row.labels.map { it.genre.label }) }

    @Test
    fun `a row per level, in the header's order, the track, each artist in credit order, the album`() {
        val genres =
            combineGenres(
                mapOf(
                    LabelSource.LASTFM to
                        album("folk rock") + artist("Ian Tyson", "country") + artist("Ian", "folk") +
                        track("soft rock", "pop"),
                ),
                listOf("Ian", "Ian Tyson"),
            )
        assertEquals(
            listOf(
                Triple(GenreLevel.TRACK, null, listOf("Soft Rock", "Pop")),
                Triple(GenreLevel.ARTIST, "Ian", listOf("Folk")),
                Triple(GenreLevel.ARTIST, "Ian Tyson", listOf("Country")),
                Triple(GenreLevel.ALBUM, null, listOf("Folk Rock")),
            ),
            genres.shown(),
        )
        assertEquals(listOf(GenreLevel.TRACK, GenreLevel.ARTIST, GenreLevel.ALBUM), genres.levels)
        assertFalse(genres.oneLevel)
    }

    @Test
    fun `a row combines its sources' labels, crediting each, a genre on two levels shows on each`() {
        val genres =
            combineGenres(
                mapOf(
                    LabelSource.MUSICBRAINZ to track("rock", "folk") + artist("A", "rock"),
                    LabelSource.LASTFM to track("rock") + album("rock"),
                ),
                listOf("A"),
            )
        assertEquals(
            listOf(
                Triple(GenreLevel.TRACK, null, listOf("Rock", "Folk")),
                Triple(GenreLevel.ARTIST, "A", listOf("Rock")),
                Triple(GenreLevel.ALBUM, null, listOf("Rock")),
            ),
            genres.shown(),
        )
        assertEquals(
            listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ),
            genres.rows
                .first()
                .labels
                .first()
                .credits
                .map { it.source },
        )
    }

    @Test
    fun `at most five labels a row`() {
        val many = arrayOf("rock", "pop", "folk", "jazz", "blues", "soul", "funk")
        val genres = combineGenres(mapOf(LabelSource.LASTFM to track(*many) + album(*many)), emptyList())
        assertTrue(genres.rows.all { it.labels.size == GenreVocabulary.MAX_LABELS })
    }

    @Test
    fun `folded, the most specific level shows, the track's, else the album's, else the artists' together`() {
        val artists = artist("A", "rock", "pop") + artist("B", "pop", "jazz")

        fun folded(vararg labels: List<GenreLabel>) =
            combineGenres(mapOf(LabelSource.LASTFM to labels.toList().flatten()), listOf("A", "B"))
                .folded
                .map { it.row.level to it.label.genre.label }

        assertEquals(listOf(GenreLevel.TRACK to "Soft Rock"), folded(artists, album("folk"), track("soft rock")))
        assertEquals(listOf(GenreLevel.ALBUM to "Folk"), folded(artists, album("folk")))
        assertEquals(
            listOf(GenreLevel.ARTIST to "Rock", GenreLevel.ARTIST to "Pop", GenreLevel.ARTIST to "Jazz"),
            folded(artists),
        )
    }

    @Test
    fun `labels of one level only, and none`() {
        val one =
            combineGenres(mapOf(LabelSource.LASTFM to artist("A", "rock") + artist("B", "jazz")), listOf("A", "B"))
        assertTrue(one.oneLevel)
        assertEquals(2, one.rows.size)

        val none = combineGenres(mapOf(LabelSource.LASTFM to track("seen live")), listOf("A"))
        assertTrue(none.isEmpty)
        assertEquals(emptyList(), none.folded)
    }

    @Test
    fun `an artist's label opens a genre card as before, the track's and the album's do not`() {
        val genres =
            combineGenres(mapOf(LabelSource.LASTFM to track("rock") + artist("A", "rock") + album("rock")), listOf("A"))
        assertEquals(listOf(false, true, false), genres.rows.map { it.labels.single().opensCard })
    }
}
