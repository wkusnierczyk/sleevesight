package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.StatedGenre
import app.sleevesight.core.genres.WikidataLabels
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wikidata's genres may reach a prompt (#1098): Wikidata is CC0, so a genre it gives counts as another
 * source's than MusicBrainz's, whose tags never reach one (D12). A genre MusicBrainz alone gives still
 * stays out.
 */
class WikidataGenresPromptTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    @Test
    fun `a genre Wikidata gives with MusicBrainz may reach a prompt, one MusicBrainz alone gives may not`() {
        val musicBrainz = vocabulary.labelsFor(listOf("electropop", "rock"), level = GenreLevel.TRACK)
        val wikidata =
            WikidataLabels(vocabulary).labels(listOf(StatedGenre("electropop", GenreLevel.TRACK, null, ITEM)))
        val genres =
            combineGenres(
                mapOf(LabelSource.MUSICBRAINZ to musicBrainz, LabelSource.WIKIDATA to wikidata),
                listOf("Pet Shop Boys"),
            )
        assertEquals(
            listOf(listOf(LabelSource.MUSICBRAINZ, LabelSource.WIKIDATA), listOf(LabelSource.MUSICBRAINZ)),
            genres.rows
                .single()
                .labels
                .map { label -> label.credits.map { it.source } },
        )
        val playing = nowPlaying("It's a Sin", "Pet Shop Boys")
        val track = TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), emptyMap(), genres = genres)
        assertTrue(GenreReasons.prompted(track, "electropop"))
        assertFalse(GenreReasons.prompted(track, "rock"))
    }

    @Test
    fun `an artist's genre Wikidata gives opens its genre card, as Last fm's does`() {
        val label =
            combineGenres(
                mapOf(
                    LabelSource.WIKIDATA to
                        WikidataLabels(
                            vocabulary,
                        ).labels(listOf(StatedGenre("synth-pop", GenreLevel.ARTIST, "A", ITEM))),
                ),
                listOf("A"),
            ).rows
                .single()
                .labels
                .single()
        assertTrue(label.opensCard)
    }

    private companion object {
        const val ITEM = "https://www.wikidata.org/wiki/Q2629703"
    }
}
