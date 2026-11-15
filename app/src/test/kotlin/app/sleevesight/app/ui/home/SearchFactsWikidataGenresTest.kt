package app.sleevesight.app.ui.home

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.StatedGenre
import app.sleevesight.core.genres.WikidataLabels
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.platform.JvmResourceReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Search with AI*'s facts (`searchFacts`) with Wikidata's genres (#1098): Wikidata is CC0, so a genre
 * it gives reaches the prompt, with MusicBrainz or alone; one MusicBrainz alone gives does not (D12).
 */
class SearchFactsWikidataGenresTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    @Test
    fun `a genre Wikidata gives reaches the prompt, with MusicBrainz or alone, and MusicBrainz alone does not`() {
        val item = "https://www.wikidata.org/wiki/Q1"
        val genres =
            combineGenres(
                mapOf(
                    LabelSource.MUSICBRAINZ to vocabulary.labelsFor(listOf("disco", "funk"), level = GenreLevel.TRACK),
                    LabelSource.WIKIDATA to
                        WikidataLabels(vocabulary).labels(
                            listOf(
                                StatedGenre("disco", GenreLevel.TRACK, null, item),
                                StatedGenre("new wave", GenreLevel.TRACK, null, item),
                            ),
                        ),
                ),
                listOf("A"),
            )
        assertEquals(
            setOf("Disco", "Funk", "New Wave"),
            genres.rows
                .single()
                .labels
                .map { it.genre.label }
                .toSet(),
        )
        val facts = searchFacts(HomeFixtures.track().copy(genres = genres), HomeFixtures.sundown)
        assertEquals(setOf("Disco", "New Wave"), facts.genres.toSet())
    }
}
