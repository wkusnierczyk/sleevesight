package app.sleevesight.core.genres

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ties between labels of equal weight go by [LABEL_TIE_ORDER] (#1019, #1098): Last.fm's, then
 * MusicBrainz's, then Wikidata's, whatever order the sources are declared in ([LabelSource], which
 * lists them otherwise) or given in.
 */
class LabelTieOrderTest {
    private fun label(
        tag: String,
        weight: Double,
    ) = GenreLabel(tag, tag, tag, level = GenreLevel.TRACK, weight = weight)

    @Test
    fun `the tie order names every source once, Last fm first, apart from the declared order`() {
        assertEquals(listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ, LabelSource.WIKIDATA), LABEL_TIE_ORDER)
        assertEquals(LabelSource.entries.toSet(), LABEL_TIE_ORDER.toSet())
    }

    @Test
    fun `at equal weight Last fm's label comes before MusicBrainz's and Wikidata's`() {
        // MusicBrainz's alone counts at half: 1.0 weighs 0.5, as Last.fm's and Wikidata's 0.5 do.
        val row =
            combineLabels(
                mapOf(
                    LabelSource.WIKIDATA to listOf(label("soul", 0.5)),
                    LabelSource.MUSICBRAINZ to listOf(label("rock", 1.0)),
                    LabelSource.LASTFM to listOf(label("pop", 0.5)),
                ),
            )
        assertEquals(listOf("pop", "rock", "soul"), row.map { it.genre.identity })
    }
}
