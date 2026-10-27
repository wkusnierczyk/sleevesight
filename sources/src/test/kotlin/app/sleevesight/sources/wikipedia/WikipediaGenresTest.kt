package app.sleevesight.sources.wikipedia

import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.web.ReplayNetClient
import app.sleevesight.sources.wikidata.WikidataClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A MusicBrainz genre's article (#523): its Wikidata item, the item's English article, its first two sentences. */
class WikipediaGenresTest {
    // Items by MusicBrainz genre, and their English articles.
    private class Wikidata(
        val items: Map<String, List<String>>,
        val articles: Map<String, String>,
        val failing: Boolean = false,
    ) : WikidataLookup {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(ids.associateWith { WdEntity(it, null, articles[it], emptyMap()) })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> = error("not asked")

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = error("not asked")

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> {
            asked += property to value
            return if (failing) Lookup.Failed("io") else Lookup.Found(items[value].orEmpty())
        }
    }

    private fun lead(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"query":{"pages":[{"title":"Hard bop","extract":"$escaped"}]}}"""
    }

    @Test
    fun `the definition is the article's first two sentences, credited with its address`() =
        runTest {
            val wikidata = Wikidata(mapOf(HARD_BOP to listOf("Q181010")), mapOf("Q181010" to "Hard bop"))
            val text =
                "Hard bop is a subgenre of jazz that is an extension of bebop. Hard bop incorporates " +
                    "influences from rhythm and blues. It developed in the mid-1950s.\nA second paragraph."
            val wikipedia = WikipediaClient(ReplayNetClient.ok(lead(text)))
            val found = WikipediaGenres(wikidata, wikipedia).definition(HARD_BOP)
            assertEquals(
                Lookup.Found(
                    GenreDefinition(
                        "Hard bop is a subgenre of jazz that is an extension of bebop. Hard bop incorporates " +
                            "influences from rhythm and blues.",
                        "Hard bop",
                        "https://en.wikipedia.org/wiki/Hard_bop",
                    ),
                ),
                found,
            )
            assertEquals(listOf(WikipediaGenres.MUSICBRAINZ_GENRE to HARD_BOP), wikidata.asked)
        }

    @Test
    fun `a lead that opens on an empty line still gives its first sentences`() {
        assertEquals(
            "A singer-songwriter is a musician who writes their own material.",
            WikipediaGenres.opening("\nA singer-songwriter is a musician who writes their own material."),
        )
    }

    @Test
    fun `the first item with an English article is the one quoted`() =
        runTest {
            val wikidata = Wikidata(mapOf(HARD_BOP to listOf("Q1", "Q2")), mapOf("Q2" to "Hard bop"))
            val wikipedia = WikipediaClient(ReplayNetClient.ok(lead("Hard bop is jazz.")))
            val found = WikipediaGenres(wikidata, wikipedia).definition(HARD_BOP)
            assertEquals("Hard bop", (found as Lookup.Found).value.title)
        }

    @Test
    fun `no item, or no English article, is no definition`() =
        runTest {
            val wikipedia = WikipediaClient(ReplayNetClient.ok(lead("Hard bop is jazz.")))
            val none = Wikidata(emptyMap(), emptyMap())
            assertEquals(Lookup.NotFound, WikipediaGenres(none, wikipedia).definition(HARD_BOP))
            val noArticle = Wikidata(mapOf(HARD_BOP to listOf("Q1")), emptyMap())
            assertEquals(Lookup.NotFound, WikipediaGenres(noArticle, wikipedia).definition(HARD_BOP))
        }

    @Test
    fun `a failed request is a failed lookup, which is not kept`() =
        runTest {
            val failing = Wikidata(emptyMap(), emptyMap(), failing = true)
            val wikipedia = WikipediaClient(ReplayNetClient.ok(lead("Hard bop is jazz.")))
            assertEquals(Lookup.Failed("io"), WikipediaGenres(failing, wikipedia).definition(HARD_BOP))
            val found = Wikidata(mapOf(HARD_BOP to listOf("Q1")), mapOf("Q1" to "Hard bop"))
            val down = WikipediaClient(ReplayNetClient.failing(NetError.Offline("SocketTimeoutException")))
            assertIs<Lookup.Failed>(WikipediaGenres(found, down).definition(HARD_BOP))
        }

    @Test
    fun `of several items holding the genre's id, the lowest-numbered with an article is quoted, whatever the search's order`() =
        runTest {
            val articles = mapOf("Q20" to "Hard bop (later)", "Q3" to "Hard bop")
            val wikidata = Wikidata(mapOf(HARD_BOP to listOf("Q20", "Q3")), articles)
            val wikipedia = WikipediaClient(ReplayNetClient.ok(lead("Hard bop is jazz.")))
            val found = WikipediaGenres(wikidata, wikipedia).definition(HARD_BOP)
            assertEquals("Hard bop", (found as Lookup.Found).value.title)
        }

    @Test
    fun `abbreviations, initials and decimals do not cut a sentence`() {
        val text =
            "Grunge emerged in the U.S. state of Washington. It began in St. Louis in 1.5 decades. Third."
        assertEquals(
            "Grunge emerged in the U.S. state of Washington. It began in St. Louis in 1.5 decades.",
            WikipediaGenres.opening(text),
        )
    }

    @Test
    fun `a paragraph the quote splitter refuses, for an inch mark, is still split`() {
        // `WikipediaSource.split` gives nothing for a paragraph with a quote after a digit.
        val text = "Hardcore was first sold on 12\" singles. It grew fast. Later it spread."
        assertEquals(emptyList<String>(), WikipediaSource.split(text))
        assertEquals("Hardcore was first sold on 12\" singles. It grew fast.", WikipediaGenres.opening(text))
    }

    @Test
    fun `an error envelope in an answer of status 200 is a failed lookup, not none`() =
        runTest {
            val envelope = """{"error":{"code":"ratelimited","info":"You've exceeded your rate limit."}}"""
            val wikidata = WikidataClient(ReplayNetClient.ok(envelope))
            assertIs<Lookup.Failed>(wikidata.itemsWithStatement(WikipediaGenres.MUSICBRAINZ_GENRE, HARD_BOP))
            assertIs<Lookup.Failed>(wikidata.entities(listOf("Q1")))
            assertIs<Lookup.Failed>(WikipediaClient(ReplayNetClient.ok(envelope)).lead("Jazz"))
            assertIs<Lookup.Failed>(WikipediaClient(ReplayNetClient.ok(envelope)).pages(listOf("Jazz")))
        }

    @Test
    fun `a definition refused by a rate limit is not kept, and the next panel looks it up again`() =
        runTest {
            val envelope = """{"error":{"code":"ratelimited"}}"""
            val search = """{"query":{"search":[{"title":"Q8341"}]}}"""
            val entities = """{"entities":{"Q8341":{"sitelinks":{"enwiki":{"title":"Jazz"}}}}}"""
            val net = Sequenced(listOf(envelope, search, entities, lead("Jazz is a music genre.")))
            val definitions =
                GenreDefinitions(
                    WikipediaGenres(WikidataClient(net), WikipediaClient(net)),
                    GenreVocabulary.load(JvmResourceReader),
                    kept = MemoryArtistResults(),
                )
            assertNull(definitions.of("jazz"))
            assertEquals("Jazz is a music genre.", definitions.of("jazz")?.text)
            assertEquals(4, net.sent)
        }

    // Answers each request with the next of [bodies], status 200.
    private class Sequenced(
        private val bodies: List<String>,
    ) : NetClient {
        var sent = 0

        override suspend fun send(request: NetRequest): NetResult<NetResponse> =
            NetResult.Success(NetResponse(200, emptyMap(), bodies[sent++].encodeToByteArray()))

        override fun close() = Unit
    }

    private companion object {
        const val HARD_BOP = "hard-bop-mbid"
    }
}
