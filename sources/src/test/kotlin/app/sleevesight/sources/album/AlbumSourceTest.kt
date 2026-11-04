package app.sleevesight.sources.album

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.lastfm.LastFmAlbum
import app.sleevesight.sources.lastfm.LastFmAlbumInfo
import app.sleevesight.sources.musicbrainz.MbAlbum
import app.sleevesight.sources.musicbrainz.ReleaseGroups
import app.sleevesight.sources.web.AlbumWebSearch
import app.sleevesight.sources.wikipedia.WikipediaClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** An album's evidence (#522): its release group, its Wikidata item and its article, kept per album. */
class AlbumSourceTest {
    private val names = NameMatcher(JvmAccentFolder)

    // The release groups by id, and the group of each release by the release's id ([failing]: that
    // lookup fails); each asked counted.
    private class Groups(
        val albums: Map<String, MbAlbum>,
        val ofRelease: Map<String, String> = emptyMap(),
        val failing: Boolean = false,
    ) : ReleaseGroups {
        val asked = mutableListOf<String>()
        val releasesAsked = mutableListOf<String>()

        override suspend fun releaseGroup(mbid: String): Lookup<MbAlbum> {
            asked += mbid
            return albums[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
        }

        override suspend fun groupOfRelease(mbid: String): Lookup<String> {
            releasesAsked += mbid
            return when {
                failing -> Lookup.Failed("offline")
                else -> ofRelease[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
            }
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

    // Last.fm's album, as `LastFmAlbums` reads it, or its failure; each request counted.
    private class LastFm(
        val album: LastFmAlbum? = null,
        val failing: Boolean = false,
    ) : LastFmAlbumInfo {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun albumInfo(
            artist: String,
            album: String,
        ): Lookup<LastFmAlbum> {
            asked += artist to album
            return when {
                failing -> Lookup.Failed("offline")
                this.album == null -> Lookup.NotFound
                else -> Lookup.Found(this.album)
            }
        }
    }

    // A web search for an album, with the lines it finds, or its failure; each search counted, and,
    // unless [sends] is off (an album it cannot check), its request sent.
    private class Web(
        val lines: List<String> = listOf("Sundown, Gordon Lightfoot's 1974 album, topped the Billboard 200."),
        var failing: Boolean = false,
        val sends: Boolean = true,
    ) : AlbumWebSearch {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun search(
            album: String,
            artist: String,
            sent: () -> Unit,
        ): Lookup<List<EvidenceDraft>> {
            asked += album to artist
            if (sends) sent()
            if (failing) return Lookup.Failed("offline")
            return Lookup.Found(
                lines.mapIndexed { i, text ->
                    EvidenceDraft(
                        SourceId.WEB,
                        Scope.WEB,
                        text,
                        "https://example.com/$i",
                        Instant.fromEpochMilliseconds(1),
                    )
                },
            )
        }
    }

    private val summary =
        "Sundown is the tenth studio album by Canadian singer-songwriter Gordon Lightfoot. " +
            "It was released in January 1974 on Reprise Records. " +
            "<a href=\"https://www.last.fm/music/Gordon+Lightfoot/Sundown\">Read more on Last.fm</a>."

    private val lastFmSundown =
        LastFmAlbum(
            name = "Sundown",
            artist = "Gordon Lightfoot",
            mbid = "rel-1",
            url = "https://www.last.fm/music/Gordon+Lightfoot/Sundown",
            summary = summary,
        )

    // Asking MusicBrainz's group and Last.fm only, with [lastFm], [web] and [webKept] as given; the
    // group lists its two releases, unless [groups] says otherwise.
    private fun source(
        lastFm: LastFmAlbumInfo? = null,
        web: AlbumWebSearch? = null,
        kept: MemoryArtistResults = MemoryArtistResults(),
        webKept: MemoryArtistResults? = null,
        groups: Groups = Groups(mapOf("rg-sundown" to sundown.copy(releaseIds = listOf("rel-1", "rel-2")))),
    ) = AlbumSource(
        groups,
        Wikidata(emptyMap()),
        WikipediaClient(Wikipedia()),
        names,
        kept = kept,
        lastFm = lastFm,
        web = web,
        webKept = webKept ?: ArtistResults.None,
    )

    private val query = AlbumQuery("Sundown", "Gordon Lightfoot", "rg-sundown", wikipedia = false)

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

    @Test
    fun `Last fm's wiki gives the album's lines, its summary's sentences quoted with the album's page`() =
        runTest {
            val found = source(LastFm(lastFmSundown)).lookup(query)
            val lastFm = found.drafts.filter { it.source == SourceId.LASTFM }
            assertEquals(
                listOf(
                    "The Last.fm wiki of “Sundown” says: \"Sundown is the tenth studio album by Canadian " +
                        "singer-songwriter Gordon Lightfoot.\"",
                    "The Last.fm wiki of “Sundown” says: \"It was released in January 1974 on Reprise Records.\"",
                ),
                lastFm.map { it.text },
            )
            // The "Read more" link, with the full stop an album's wiki puts after it, is not a sentence.
            assertTrue(lastFm.none { "Read more" in it.text })
            assertTrue(lastFm.all { it.scope == Scope.RELEASE && it.url == lastFmSundown.url && !it.aggregate })
        }

    // Whether [found] has Last.fm's lines.
    private fun citesLastFm(found: app.sleevesight.core.sources.AlbumFound) =
        found.drafts.any { it.source == SourceId.LASTFM }

    @Test
    fun `Last fm's album with no MusicBrainz id, or no group to check it against, gives nothing, whatever its names`() =
        runTest {
            // The names those asked: still no sign that the page is this artist's, not a namesake's.
            assertFalse(citesLastFm(source(LastFm(lastFmSundown.copy(mbid = null))).lookup(query)))
            // An album MusicBrainz does not name: nothing to check the id against, so Last.fm is not asked.
            val unnamed = LastFm(lastFmSundown)
            assertFalse(citesLastFm(source(unnamed).lookup(query.copy(group = null))))
            assertTrue(unnamed.asked.isEmpty())
        }

    @Test
    fun `Last fm's album is the album when its MusicBrainz id is the group's or one of its releases`() =
        runTest {
            // One of the group's releases: the album, whatever Last.fm calls it.
            val renamed = lastFmSundown.copy(mbid = "REL-2", name = "Sundown (Remastered)")
            assertTrue(citesLastFm(source(LastFm(renamed)).lookup(query)))
            assertTrue(citesLastFm(source(LastFm(lastFmSundown.copy(mbid = "rg-sundown"))).lookup(query)))
            // Another release, while the group lists all of its own: another album, with no request.
            val groups = Groups(mapOf("rg-sundown" to sundown.copy(releaseIds = listOf("rel-1", "rel-2"))))
            assertFalse(citesLastFm(source(LastFm(lastFmSundown.copy(mbid = "rel-9")), groups = groups).lookup(query)))
            assertTrue(groups.releasesAsked.isEmpty())
        }

    @Test
    fun `an id the group's list may leave out is checked by the group MusicBrainz says its release is of`() =
        runTest {
            val many = sundown.copy(releaseIds = (1..MbAlbum.LINKED_LIMIT).map { "rel-x$it" })
            val other = lastFmSundown.copy(mbid = "rel-9")

            fun groups(
                ofRelease: Map<String, String> = emptyMap(),
                failing: Boolean = false,
            ) = Groups(mapOf("rg-sundown" to many), ofRelease, failing)

            val ours = groups(mapOf("rel-9" to "rg-sundown"))
            assertTrue(citesLastFm(source(LastFm(other), groups = ours).lookup(query)))
            assertEquals(listOf("rel-9"), ours.releasesAsked)
            // Another group's release, or one MusicBrainz does not know: not the album.
            assertFalse(citesLastFm(source(LastFm(other), groups = groups(mapOf("rel-9" to "rg-other"))).lookup(query)))
            assertFalse(citesLastFm(source(LastFm(other), groups = groups()).lookup(query)))
            // A group with no releases listed is checked the same way.
            val bare = Groups(mapOf("rg-sundown" to sundown), mapOf("rel-9" to "rg-sundown"))
            assertTrue(citesLastFm(source(LastFm(other), groups = bare).lookup(query)))
            // A request that fails: nothing, and nothing kept.
            val kept = MemoryArtistResults()
            val failed = source(LastFm(other), groups = groups(failing = true), kept = kept).lookup(query)
            assertFalse(citesLastFm(failed))
            assertTrue(failed.unreachable)
            assertTrue(kept.keys(AlbumSource.PART).isEmpty())
        }

    @Test
    fun `with MusicBrainz off, only an id that is the group's own makes it the album`() =
        runTest {
            val off = query.copy(musicBrainz = false)
            val groups = Groups(emptyMap(), mapOf("rel-1" to "rg-sundown"))
            assertFalse(citesLastFm(source(LastFm(lastFmSundown), groups = groups).lookup(off)))
            assertTrue(groups.releasesAsked.isEmpty())
            assertTrue(citesLastFm(source(LastFm(lastFmSundown.copy(mbid = "rg-sundown"))).lookup(off)))
        }

    @Test
    fun `Last fm is asked only while it is switched on, and its lines are kept with the album's`() =
        runTest {
            val lastFm = LastFm(lastFmSundown)
            val kept = MemoryArtistResults()
            val source = source(lastFm, kept = kept)
            assertTrue(source.lookup(query.copy(lastFm = false)).drafts.none { it.source == SourceId.LASTFM })
            assertTrue(lastFm.asked.isEmpty())
            val first = source.lookup(query)
            val again = source.lookup(query)
            assertEquals(first.drafts, again.drafts)
            assertEquals(1, lastFm.asked.size)
        }

    @Test
    fun `Last fm that fails is unreachable, and nothing is kept`() =
        runTest {
            val kept = MemoryArtistResults()
            val found = source(LastFm(failing = true), kept = kept).lookup(query)
            assertTrue(found.unreachable)
            assertTrue(kept.keys(AlbumSource.PART).isEmpty())
        }

    @Test
    fun `a lookup never searches the web, and says a search is due until one is kept`() =
        runTest {
            val web = Web()
            val webKept = MemoryArtistResults()
            val source = source(web = web, webKept = webKept)
            val before = source.lookup(query)
            assertTrue(before.webDue)
            assertTrue(before.drafts.none { it.source == SourceId.WEB })
            assertTrue(web.asked.isEmpty())

            val searched = checkNotNull(source.searchWeb(query))
            assertEquals(listOf("Sundown" to "Gordon Lightfoot"), web.asked)
            assertEquals(1, webKept.keys(AlbumSource.WEB_PART).size)

            // Found again with no search: the same lines, so the same fingerprint.
            val after = source.lookup(query)
            assertFalse(after.webDue)
            assertEquals(searched, after.drafts.filter { it.source == SourceId.WEB })
            assertEquals(searched, source.searchWeb(query))
            assertEquals(1, web.asked.size)
        }

    @Test
    fun `the caller is told of each request sent, not of lines kept or an album that sends none`() =
        runTest {
            var sent = 0
            val source = source(web = Web(failing = true))
            // A request sent that fails is still one sent.
            assertNull(source.searchWeb(query) { sent++ })
            assertEquals(1, sent)
            val found = source(web = Web())
            found.searchWeb(query) { sent++ }
            found.searchWeb(query) { sent++ }
            assertEquals(2, sent)
            source(web = Web(sends = false)).searchWeb(query) { sent++ }
            assertEquals(2, sent)
        }

    @Test
    fun `a web search that finds nothing is kept too, so it is not made again`() =
        runTest {
            val web = Web(lines = emptyList())
            val source = source(web = web)
            assertEquals(emptyList(), source.searchWeb(query))
            assertFalse(source.lookup(query).webDue)
            source.searchWeb(query)
            assertEquals(1, web.asked.size)
        }

    @Test
    fun `a web search that fails keeps nothing, and is due again`() =
        runTest {
            val web = Web(failing = true)
            val webKept = MemoryArtistResults()
            val source = source(web = web, webKept = webKept)
            assertNull(source.searchWeb(query))
            assertTrue(webKept.keys(AlbumSource.WEB_PART).isEmpty())
            assertTrue(source.lookup(query).webDue)
        }

    @Test
    fun `the web is not searched while it is switched off, or without an engine`() =
        runTest {
            val web = Web()
            val off = query.copy(web = false)
            assertFalse(source(web = web).lookup(off).webDue)
            assertNull(source(web = web).searchWeb(off))
            assertFalse(source().lookup(query).webDue)
            assertNull(source().searchWeb(query))
            assertTrue(web.asked.isEmpty())
        }
}
