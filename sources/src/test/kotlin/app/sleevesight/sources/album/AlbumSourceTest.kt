package app.sleevesight.sources.album

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.musicbrainz.MbAlbum
import app.sleevesight.sources.musicbrainz.ReleaseGroups
import app.sleevesight.sources.wikipedia.WikipediaClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An album's evidence (#522): its release group, its Wikidata item and its article, kept per album. */
class AlbumSourceTest {
    private val names = NameMatcher(JvmAccentFolder)

    private class Groups(
        val albums: Map<String, MbAlbum>,
    ) : ReleaseGroups {
        val asked = mutableListOf<String>()

        override suspend fun releaseGroup(mbid: String): Lookup<MbAlbum> {
            asked += mbid
            return albums[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
        }
    }

    private class Wikidata(
        val entities: Map<String, WdEntity>,
        val byGroup: Map<String, List<String>> = emptyMap(),
        val labels: Map<String, String> = emptyMap(),
    ) : WikidataLookup {
        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(ids.mapNotNull { entities[it] }.associateBy { it.id })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
            Lookup.Found(ids.mapNotNull { id -> labels[id]?.let { id to it } }.toMap())

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = error("not asked")

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> = Lookup.Found(byGroup[value].orEmpty())
    }

    // Wikipedia's answers: the pages a lookup by name finds (by title, with their items), and each article's lead.
    private class Wikipedia(
        val pages: Map<String, String> = emptyMap(),
        val leads: Map<String, String> = emptyMap(),
        val failing: Boolean = false,
    ) : NetClient {
        val sent = mutableListOf<NetRequest>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            sent += request
            if (failing) return NetResult.Failure(NetError.Offline("io"))
            val title = request.query["titles"].orEmpty()
            val body =
                if (request.query["prop"] == "extracts") {
                    val lead = leads[title].orEmpty().replace("\"", "\\\"")
                    """{"query":{"pages":[{"title":"$title","extract":"$lead"}]}}"""
                } else {
                    val found =
                        title.split('|').mapNotNull { asked ->
                            pages[asked]?.let { """{"title":"$asked","pageprops":{"wikibase_item":"$it"}}""" }
                        }
                    """{"query":{"pages":[${found.joinToString(",")}]}}"""
                }
            return NetResult.Success(NetResponse(200, emptyMap(), body.encodeToByteArray()))
        }

        override fun close() = Unit
    }

    private val sundown =
        MbAlbum(
            mbid = "rg-sundown",
            title = "Sundown",
            primaryType = "Album",
            secondaryTypes = emptyList(),
            firstReleaseDate = "1974-01",
            credit = "Gordon Lightfoot",
            wikidataIds = listOf("Q1"),
        )

    private val item =
        WdEntity(
            "Q1",
            "Sundown",
            "Sundown (Gordon Lightfoot album)",
            mapOf(Wd.INSTANCE_OF to listOf("Q208569"), Wd.RECORD_LABEL to listOf("Q2"), Wd.PERFORMER to listOf("Q3")),
        )

    private val lead =
        "Sundown is the tenth studio album by Canadian singer-songwriter Gordon Lightfoot. It was released in " +
            "January 1974. Its title track became his only number one."

    @Test
    fun `an album's lines come from its release group, its Wikidata item and its article`() =
        runTest {
            val wikipedia = Wikipedia(leads = mapOf("Sundown (Gordon Lightfoot album)" to lead))
            val source =
                AlbumSource(
                    Groups(mapOf("rg-sundown" to sundown)),
                    Wikidata(mapOf("Q1" to item), labels = mapOf("Q2" to "Reprise Records")),
                    WikipediaClient(wikipedia),
                    names,
                )
            val found = source.lookup(AlbumQuery("Sundown", "Gordon Lightfoot", "rg-sundown"))
            assertFalse(found.unreachable)
            assertEquals(
                listOf(
                    SourceId.MUSICBRAINZ to
                        "“Sundown” is an album by Gordon Lightfoot, first released in January 1974.",
                    // The lead's sentences that name the album: the first, here.
                    SourceId.WIKIPEDIA to
                        "Sundown is the tenth studio album by Canadian singer-songwriter Gordon Lightfoot.",
                    SourceId.WIKIDATA to "“Sundown” was released on Reprise Records.",
                ),
                found.drafts.map { it.source to it.text },
            )
            assertTrue(found.drafts.all { it.scope == Scope.RELEASE })
            assertEquals("https://musicbrainz.org/release-group/rg-sundown", found.drafts.first().url)
        }

    @Test
    fun `an album MusicBrainz does not name is found by its name, as an album by the artist`() =
        runTest {
            val other = WdEntity("Q9", "Sundown", "Sundown (film)", mapOf(Wd.INSTANCE_OF to listOf("Q11424")))
            val wikipedia =
                Wikipedia(
                    pages = mapOf("Sundown (Gordon Lightfoot album)" to "Q1", "Sundown" to "Q9"),
                    leads = mapOf("Sundown (Gordon Lightfoot album)" to lead),
                )
            val groups = Groups(emptyMap())
            val source =
                AlbumSource(
                    groups,
                    Wikidata(mapOf("Q1" to item, "Q9" to other), labels = mapOf("Q3" to "Gordon Lightfoot")),
                    WikipediaClient(wikipedia),
                    names,
                )
            val found = source.lookup(AlbumQuery("Sundown", "Gordon Lightfoot", group = null))
            assertTrue(groups.asked.isEmpty())
            assertEquals(SourceId.WIKIPEDIA, found.drafts.first().source)
            assertTrue(
                found.drafts
                    .first()
                    .text
                    .startsWith("Sundown is the tenth studio album"),
            )
        }

    @Test
    fun `an album of another artist's of the same name is not taken`() =
        runTest {
            val wikipedia =
                Wikipedia(
                    pages = mapOf("Sundown (album)" to "Q1"),
                    leads =
                        mapOf(
                            "Sundown (Gordon Lightfoot album)" to lead,
                        ),
                )
            val source =
                AlbumSource(
                    Groups(emptyMap()),
                    Wikidata(mapOf("Q1" to item), labels = mapOf("Q3" to "Gordon Lightfoot")),
                    WikipediaClient(wikipedia),
                    names,
                )
            assertTrue(source.lookup(AlbumQuery("Sundown", "Rank and File", group = null)).drafts.isEmpty())
        }

    @Test
    fun `what was found is kept per album, so its next track asks nothing`() =
        runTest {
            val wikipedia = Wikipedia(leads = mapOf("Sundown (Gordon Lightfoot album)" to lead))
            val groups = Groups(mapOf("rg-sundown" to sundown))
            val source =
                AlbumSource(
                    groups,
                    Wikidata(mapOf("Q1" to item)),
                    WikipediaClient(wikipedia),
                    names,
                    kept = MemoryArtistResults(),
                )
            val query = AlbumQuery("Sundown", "Gordon Lightfoot", "rg-sundown")
            val first = source.lookup(query)
            val again = source.lookup(query)
            assertEquals(first.drafts, again.drafts)
            assertEquals(1, groups.asked.size)
            assertEquals(1, wikipedia.sent.size)
        }

    @Test
    fun `a source that fails is unreachable, and nothing is kept`() =
        runTest {
            val wikipedia = Wikipedia(failing = true)
            val kept = MemoryArtistResults()
            val source =
                AlbumSource(
                    Groups(mapOf("rg-sundown" to sundown)),
                    Wikidata(mapOf("Q1" to item)),
                    WikipediaClient(wikipedia),
                    names,
                    kept = kept,
                )
            val found = source.lookup(AlbumQuery("Sundown", "Gordon Lightfoot", "rg-sundown"))
            assertTrue(found.unreachable)
            // MusicBrainz's line stands.
            assertEquals(SourceId.MUSICBRAINZ, found.drafts.single().source)
            assertTrue(kept.keys(AlbumSource.PART).isEmpty())
        }

    @Test
    fun `the types read as MusicBrainz gives them`() {
        assertEquals("album", AlbumSource.typeOf(sundown))
        assertEquals("live album", AlbumSource.typeOf(sundown.copy(secondaryTypes = listOf("Live"))))
        assertEquals("EP", AlbumSource.typeOf(sundown.copy(primaryType = "EP")))
        assertEquals("release", AlbumSource.typeOf(sundown.copy(primaryType = null)))
    }
}
