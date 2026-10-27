package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a genre is (#523): found through its MusicBrainz genre, once per genre, whatever the track. */
class GenreDefinitionsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private val jazz =
        GenreDefinition(
            "Jazz is a music genre that originated in the African-American communities of New Orleans.",
            "Jazz",
            "https://en.wikipedia.org/wiki/Jazz",
        )

    // Answers [answer] for every genre, counting the genres asked for.
    private class Articles(
        var answer: (String) -> Lookup<GenreDefinition>,
    ) : GenreArticles {
        val asked = mutableListOf<String>()

        override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> {
            asked += musicBrainzGenre
            return answer(musicBrainzGenre)
        }
    }

    @Test
    fun `the vocabulary gives a genre its MusicBrainz genre, by identity, for every spelling`() {
        assertEquals(JAZZ, vocabulary.musicBrainzGenre("jazz"))
        // `hip-hop` and `hip hop` are one genre, MusicBrainz's `hip hop`.
        val hipHop = "52faa157-6bad-4d86-a0ab-d4dec7d2513c"
        assertEquals(hipHop, vocabulary.musicBrainzGenre(checkNotNull(vocabulary.identity("hip-hop"))))
        assertEquals(hipHop, vocabulary.musicBrainzGenre(checkNotNull(vocabulary.identity("hip hop"))))
        // `r&b` and `rnb` are synonyms: MusicBrainz's `r&b` for both.
        assertEquals(vocabulary.musicBrainzGenre(checkNotNull(vocabulary.identity("rnb"))), R_AND_B)
        // Not one of MusicBrainz's genres.
        assertNull(vocabulary.musicBrainzGenre(checkNotNull(vocabulary.identity("british invasion"))))
    }

    @Test
    fun `a genre's definition is looked up once for every track that has the genre`() =
        runTest {
            val articles = Articles { Lookup.Found(jazz) }
            val kept = MemoryArtistResults()
            val definitions = GenreDefinitions(articles, vocabulary, kept = kept)
            // Three tracks' panels open on jazz: one lookup, the rest from what was kept.
            repeat(3) { assertEquals(jazz, definitions.of("jazz")) }
            assertEquals(listOf(JAZZ), articles.asked)
            assertEquals(setOf(JAZZ), kept.keys(GenreDefinitions.PART))
            // A process started afresh reads what the store kept, and asks nothing.
            assertEquals(jazz, GenreDefinitions(articles, vocabulary, kept = kept).of("jazz"))
            assertEquals(1, articles.asked.size)
        }

    @Test
    fun `without a store that persists, a definition is kept in memory`() =
        runTest {
            val articles = Articles { Lookup.Found(jazz) }
            val definitions = GenreDefinitions(articles, vocabulary)
            repeat(2) { definitions.of("jazz") }
            assertEquals(listOf(JAZZ), articles.asked)
        }

    @Test
    fun `a genre with no article has no definition, and is not asked for again`() =
        runTest {
            val articles = Articles { Lookup.NotFound }
            val definitions = GenreDefinitions(articles, vocabulary, kept = MemoryArtistResults())
            assertNull(definitions.of("jazz"))
            assertNull(definitions.of("jazz"))
            assertEquals(1, articles.asked.size)
        }

    @Test
    fun `a failed lookup gives no definition, and the next panel asks again`() =
        runTest {
            val articles = Articles { Lookup.Failed("io") }
            val definitions = GenreDefinitions(articles, vocabulary, kept = MemoryArtistResults())
            assertNull(definitions.of("jazz"))
            articles.answer = { Lookup.Found(jazz) }
            assertEquals(jazz, definitions.of("jazz"))
            assertEquals(2, articles.asked.size)
        }

    @Test
    fun `a genre that is not one of MusicBrainz's asks nothing`() =
        runTest {
            val articles = Articles { Lookup.Found(jazz) }
            val definitions = GenreDefinitions(articles, vocabulary)
            assertNull(definitions.of(checkNotNull(vocabulary.identity("british invasion"))))
            assertEquals(emptyList(), articles.asked)
        }

    private companion object {
        const val JAZZ = "a715278f-1580-409f-8078-4ffbc800e08b"
        const val R_AND_B = "31be54b2-4d0c-42df-aa44-c496c7b4c3c3"
    }
}
