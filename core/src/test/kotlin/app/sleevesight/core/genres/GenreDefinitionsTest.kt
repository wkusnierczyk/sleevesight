package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What a genre is (#523): found through its MusicBrainz genre, or the article the vocabulary names
 * for it (#852), once per genre, whatever the track.
 */
class GenreDefinitionsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private val jazz =
        GenreDefinition(
            "Jazz is a music genre that originated in the African-American communities of New Orleans.",
            "Jazz",
            "https://en.wikipedia.org/wiki/Jazz",
        )

    // Answers [answer] for every genre and every article, counting the genres asked for and the articles.
    private class Articles(
        var answer: (String) -> Lookup<GenreDefinition>,
    ) : GenreArticles {
        val asked = mutableListOf<String>()
        val titles = mutableListOf<String>()

        override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> {
            asked += musicBrainzGenre
            return answer(musicBrainzGenre)
        }

        override suspend fun article(title: String): Lookup<GenreDefinition> {
            titles += title
            return answer(title)
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
            repeat(3) { assertEquals(Lookup.Found(jazz), definitions.lookup("jazz")) }
            assertEquals(listOf(JAZZ), articles.asked)
            assertEquals(setOf(JAZZ), kept.keys(GenreDefinitions.PART))
            // A process started afresh reads what the store kept, and asks nothing.
            assertEquals(Lookup.Found(jazz), GenreDefinitions(articles, vocabulary, kept = kept).lookup("jazz"))
            assertEquals(1, articles.asked.size)
        }

    @Test
    fun `without a store that persists, a definition is kept in memory`() =
        runTest {
            val articles = Articles { Lookup.Found(jazz) }
            val definitions = GenreDefinitions(articles, vocabulary)
            repeat(2) { definitions.lookup("jazz") }
            assertEquals(listOf(JAZZ), articles.asked)
        }

    @Test
    fun `a genre with no article has no definition, and is not asked for again`() =
        runTest {
            val articles = Articles { Lookup.NotFound }
            val definitions = GenreDefinitions(articles, vocabulary, kept = MemoryArtistResults())
            assertEquals(Lookup.NotFound, definitions.lookup("jazz"))
            assertEquals(Lookup.NotFound, definitions.lookup("jazz"))
            assertEquals(1, articles.asked.size)
        }

    @Test
    fun `a failed lookup gives no definition, and the next panel asks again`() =
        runTest {
            val articles = Articles { Lookup.Failed("io") }
            val definitions = GenreDefinitions(articles, vocabulary, kept = MemoryArtistResults())
            assertEquals(Lookup.Failed(GenreDefinitions.FAILED), definitions.lookup("jazz"))
            articles.answer = { Lookup.Found(jazz) }
            assertEquals(Lookup.Found(jazz), definitions.lookup("jazz"))
            assertEquals(2, articles.asked.size)
        }

    @Test
    fun `a genre with neither a MusicBrainz genre nor an article named has none, and asks nothing`() =
        runTest {
            val articles = Articles { Lookup.Found(jazz) }
            val definitions = GenreDefinitions(articles, vocabulary)
            assertEquals(Lookup.NotFound, definitions.lookup(checkNotNull(vocabulary.identity("pop vocal group"))))
            assertEquals(emptyList(), articles.asked)
            assertEquals(emptyList(), articles.titles)
        }

    @Test
    fun `"Scattershot"'s genres each have a definition, Acoustic's from the article the vocabulary names`() =
        runTest {
            // #852: Caitlin Canty's "Scattershot" has Last.fm's *folk*, *singer-songwriter* and
            // *acoustic*. *Acoustic* is not one of MusicBrainz's genres, and *singer-songwriter*'s
            // has no English article; both are defined by the article the vocabulary names.
            val acoustic =
                GenreDefinition(
                    "Acoustic music is music that solely or primarily uses instruments that produce sound " +
                        "through acoustic means, as opposed to electric or electronic means.",
                    "Acoustic music",
                    "https://en.wikipedia.org/wiki/Acoustic_music",
                )
            val articles = Articles { if (it == "Acoustic music") Lookup.Found(acoustic) else Lookup.Found(jazz) }
            val kept = MemoryArtistResults()
            val definitions = GenreDefinitions(articles, vocabulary, kept = kept)
            val labels = vocabulary.labelsFor(listOf("Folk", "Singer-Songwriter", "Acoustic"))
            assertEquals(listOf("Folk", "Singer-Songwriter", "Acoustic"), labels.map { it.label })
            for (label in labels) assertIs<Lookup.Found<GenreDefinition>>(definitions.lookup(label.identity))
            assertEquals(Lookup.Found(acoustic), definitions.lookup(labels.last().identity))
            assertEquals(listOf(vocabulary.musicBrainzGenre("folk")), articles.asked)
            assertEquals(listOf("Singer-songwriter", "Acoustic music"), articles.titles)
            // Kept by the article, so it is not asked again, and never taken for a MusicBrainz genre.
            assertEquals(
                setOf(vocabulary.musicBrainzGenre("folk"), "enwiki:Singer-songwriter", "enwiki:Acoustic music"),
                kept.keys(GenreDefinitions.PART),
            )
        }

    @Test
    fun `an article the vocabulary names that is not found is no definition, and is not asked for again`() =
        runTest {
            val articles = Articles { Lookup.NotFound }
            val definitions = GenreDefinitions(articles, vocabulary, kept = MemoryArtistResults())
            repeat(2) { assertEquals(Lookup.NotFound, definitions.lookup("acoustic")) }
            assertEquals(listOf("Acoustic music"), articles.titles)
        }

    @Test
    fun `a failed lookup of an article the vocabulary names is not kept, and the next panel asks again`() =
        runTest {
            val articles = Articles { Lookup.Failed("offline") }
            val definitions = GenreDefinitions(articles, vocabulary, kept = MemoryArtistResults())
            repeat(2) { assertEquals(Lookup.Failed(GenreDefinitions.FAILED), definitions.lookup("acoustic")) }
            assertEquals(listOf("Acoustic music", "Acoustic music"), articles.titles)
        }

    @Test
    fun `the articles the vocabulary names, each for a genre MusicBrainz cannot define`() {
        // #852, each article's lead checked on 2026-11-02 to say what the tag means. Each tag has no
        // MusicBrainz genre that fits, or, for *classic rock* and *singer-songwriter*, a genre whose
        // Wikidata item has no English article (2026-10-27).
        val named =
            mapOf(
                "acoustic" to "Acoustic music",
                "british invasion" to "British Invasion",
                "rock francais" to "Rock music in France",
                "classic rock" to "Classic rock",
                "singer-songwriter" to "Singer-songwriter",
                "score" to "Film score",
                "soundtrack" to "Soundtrack",
                "choral" to "Choir",
            )

        fun articleOf(tag: String) = vocabulary.wikipediaArticle(checkNotNull(vocabulary.identity(tag)))
        assertEquals(named.keys, vocabulary.tags.filter { articleOf(it) != null }.toSet())
        for ((tag, title) in named) assertEquals(title, articleOf(tag), tag)
    }

    @Test
    fun `a bare tag has the genre it stands for, as listeners use it`() {
        // #663: Last.fm's *alternative* is alternative rock, which has an English article.
        val alternativeRock = vocabulary.musicBrainzGenre("alternativerock")
        assertEquals("ceeaa283-5d7b-4202-8d1d-e25d116b2a18", alternativeRock)
        assertEquals(alternativeRock, vocabulary.musicBrainzGenre(checkNotNull(vocabulary.identity("alternative"))))
        // The nearest genre with an article, for a tag that is not one of MusicBrainz's or whose own
        // genre has none (`genre-vocabulary.json`'s `about`).
        val nearest =
            mapOf(
                "indie" to "ccd19ebf-052c-4afe-8ad9-fbb0a73f94a7", // indie rock
                "goth" to "d9858aef-25bf-478b-b135-b900aa1f0fd1", // gothic rock
                "gothic" to "d9858aef-25bf-478b-b135-b900aa1f0fd1", // gothic rock
                "metal" to "a6719055-99c4-47eb-beaa-71081f2376f9", // heavy metal
                "punk" to "bd7e1d40-43b1-4ba5-97b3-05b891466962", // punk rock
                "chanson francaise" to "b7864789-29e6-4965-84e4-463baaa869df", // chanson française
                "rap" to "52faa157-6bad-4d86-a0ab-d4dec7d2513c", // hip hop
                "neoclassical" to "27f6e0f8-8d02-4f15-ac9f-45c5798978b7", // neoclassical new age
            )
        for ((tag, genre) in nearest) {
            assertEquals(genre, vocabulary.musicBrainzGenre(checkNotNull(vocabulary.identity(tag))), tag)
        }
    }

    @Test
    fun `the tags with no MusicBrainz genre are left so on purpose`() {
        // Why each has none: MusicBrainz has no such genre (genre list, 2026-10-27), and the nearest
        // would describe something else (#663). All but two are defined by the article the vocabulary
        // names (#852, the test above).
        val onPurpose =
            mapOf(
                "acoustic" to "a way of playing, not a genre; acoustic rock would misdescribe acoustic folk",
                "british invasion" to "a historical wave of bands; beat music would misdescribe its blues side",
                "rock francais" to "MusicBrainz has no French rock; rock alone loses what the tag says",
                "pop vocal group" to "a kind of act, not a genre; MusicBrainz's close harmony has no article",
                "soundcloud rap" to "MusicBrainz has none; cloud rap and emo rap are other styles",
                "soundtrack" to "a kind of release in MusicBrainz, not a genre",
                "score" to "a kind of release in MusicBrainz, not a genre",
                "choral" to "MusicBrainz has none; choral symphony is one form of it",
            )
        val without =
            vocabulary.tags.filter {
                vocabulary.musicBrainzGenre(checkNotNull(vocabulary.identity(it))) ==
                    null
            }
        assertEquals(onPurpose.keys, without.toSet())
    }

    @Test
    fun `the tags with no definition at all are left so on purpose`() {
        // Each has no MusicBrainz genre and no article the vocabulary names, and its panel says there
        // is no definition from Wikipedia for it (#663). English Wikipedia has no article of its own
        // for either, only a section of another (2026-11-02): *Vocal group* is a section of *Musical
        // ensemble*, *SoundCloud rap* of *SoundCloud*.
        val without =
            vocabulary.tags.filter {
                val identity = checkNotNull(vocabulary.identity(it))
                vocabulary.musicBrainzGenre(identity) == null && vocabulary.wikipediaArticle(identity) == null
            }
        assertEquals(setOf("pop vocal group", "soundcloud rap"), without.toSet())
    }

    private companion object {
        const val JAZZ = "a715278f-1580-409f-8078-4ffbc800e08b"
        const val R_AND_B = "31be54b2-4d0c-42df-aa44-c496c7b4c3c3"
    }
}
