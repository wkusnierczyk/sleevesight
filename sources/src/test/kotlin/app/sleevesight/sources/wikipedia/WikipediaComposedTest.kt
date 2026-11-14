package app.sleevesight.sources.wikipedia

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.ComposedDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the music was composed, as the song's Wikidata item says (#1132): the inception (P571) of the
 * item the identity check accepted, on Wikipedia's result, from the item it reads anyway; and before
 * it, as the infobox of the item's article says (#1273), from the request for the article's lead.
 */
class WikipediaComposedTest {
    // One item, the song MusicBrainz links, with [claims] and the English article [enwiki]; no artist item.
    private class Wikidata(
        val claims: Map<String, List<String>>,
        val enwiki: String? = null,
    ) : WikidataLookup {
        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(ids.associateWith { WdEntity(it, "Sonata", enwiki, claims) })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> = Lookup.Found(emptyMap())

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(claims[property].orEmpty())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())
    }

    private val track = RecordedTracks.normalize("Piano Sonata No. 16", "Khatia Buniatishvili")

    private val resolution =
        Resolution(
            outcome = Outcome.SongArticle,
            recordingMbid = "mb-r",
            artists = listOf(ResolvedArtist("Khatia Buniatishvili", "mb-k")),
            wikidataIds = listOf("Q1"),
        )

    // Wikipedia answering every request with [reply]: no article by name, and the item's, [article],
    // read as [reply] holds it.
    private suspend fun composed(
        claims: Map<String, List<String>>,
        article: String? = null,
        reply: ReplayNetClient = ReplayNetClient.ok("""{"query":{"pages":[]}}"""),
    ): ComposedDate? {
        val adapter =
            WikipediaSource(
                WikipediaClient(reply),
                IdentityCheck(Wikidata(claims, article), RecordedTracks.names),
                RecordedTracks.names,
            )
        return adapter.lookup(track, CompletableDeferred(resolution)).composed
    }

    private val sonata = listOf("Q105543609")

    @Test
    fun `the accepted song item's inception is when its music was composed`() =
        runTest {
            val claims =
                mapOf(
                    Wd.INSTANCE_OF to listOf("Q105543609"),
                    Wd.INCEPTION to listOf("+1788-00-00T00:00:00Z"),
                    Wd.PUBLICATION_DATE to listOf("+1805-00-00T00:00:00Z"),
                )
            assertEquals(
                ComposedDate("1788", "1788", SourceId.WIKIDATA, "https://www.wikidata.org/wiki/Q1"),
                composed(claims),
            )
        }

    @Test
    fun `an item with no inception, or none accepted, dates nothing`() =
        runTest {
            assertNull(composed(mapOf(Wd.INSTANCE_OF to listOf("Q105543609"))))
            // Not a song: the check rejects it, and its inception is no one's.
            assertNull(composed(mapOf(Wd.INSTANCE_OF to listOf("Q5"), Wd.INCEPTION to listOf("+1788-00-00T00:00:00Z"))))
        }

    @Test
    fun `the article's infobox span comes before the item's one year inside it`() =
        runTest {
            val article = "Symphony No. 5 (Beethoven)"
            val claims = mapOf(Wd.INSTANCE_OF to sonata, Wd.INCEPTION to listOf("+1807-00-00T00:00:00Z"))
            val reply = ReplayNetClient.recorded("wikipedia-1273/query-symphony-no-5-beethoven.json")
            val url = "https://en.wikipedia.org/wiki/Symphony_No._5_(Beethoven)"
            assertEquals(ComposedDate("1804", "1808", SourceId.WIKIPEDIA, url), composed(claims, article, reply))
            // The article's lead was read with its section's wikitext, in one request.
            val lead = reply.sent.single { it.query["titles"] == article }
            assertEquals("extracts|revisions", lead.query["prop"])
        }

    @Test
    fun `an article whose infobox dates nothing leaves the item's inception`() =
        runTest {
            val claims = mapOf(Wd.INSTANCE_OF to sonata, Wd.INCEPTION to listOf("+1741-00-00T00:00:00Z"))
            val reply = ReplayNetClient.recorded("wikipedia-1273/query-goldberg-variations.json")
            assertEquals(
                ComposedDate("1741", "1741", SourceId.WIKIDATA, "https://www.wikidata.org/wiki/Q1"),
                composed(claims, "Goldberg Variations", reply),
            )
        }

    @Test
    fun `an article's infobox dates a work its item does not`() =
        runTest {
            val reply = ReplayNetClient.recorded("wikipedia-1273/query-cello-suites-bach.json")
            assertEquals(
                ComposedDate("1717", "1723", SourceId.WIKIPEDIA, "https://en.wikipedia.org/wiki/Cello_Suites_(Bach)"),
                composed(mapOf(Wd.INSTANCE_OF to sonata), "Cello Suites (Bach)", reply),
            )
        }
}
