package app.sleevesight.app.ui.home

import app.sleevesight.core.genres.CountedTag
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.platform.JvmResourceReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Search with AI*'s facts (`searchFacts`) under the weighed rows (#1019): D12 holds, MusicBrainz's
 * tags never reach the prompt, though a MusicBrainz-only label can now take a row's place.
 */
class SearchFactsWeighedGenresTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun labels(vararg tags: Pair<String, Int>) =
        vocabulary.sourceLabels(tags.map { (name, count) -> CountedTag(name, count) }, null, GenreLevel.ARTIST, "A")

    @Test
    fun `a MusicBrainz-only label on the weighed row stays out of the prompt, a shared one spelled as Last fm's`() {
        // MusicBrainz's Metal and Punk, counted at half as MusicBrainz's alone (0.5), take places
        // Last.fm's Jazz and Soul held before the weighing;
        // Hip-Hop, given by both, rises, its label as Last.fm, the first source, spells it.
        val genres =
            combineGenres(
                mapOf(
                    LabelSource.LASTFM to
                        labels("rock" to 100, "pop" to 90, "jazz" to 40, "soul" to 30, "hip-hop" to 15),
                    LabelSource.MUSICBRAINZ to labels("hip hop" to 20, "metal" to 20, "punk" to 20),
                ),
                listOf("A"),
            )
        assertEquals(
            listOf("Hip-Hop", "Rock", "Pop", "Metal", "Punk"),
            genres.rows
                .single()
                .labels
                .map { it.genre.label },
        )
        val facts = searchFacts(HomeFixtures.track().copy(genres = genres), HomeFixtures.sundown)
        assertEquals(listOf("Hip-Hop", "Rock", "Pop"), facts.genres)
    }
}
