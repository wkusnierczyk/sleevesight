package app.sleevesight.sources.wikipedia

import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.net.NetError
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

    private companion object {
        const val HARD_BOP = "hard-bop-mbid"
    }
}
