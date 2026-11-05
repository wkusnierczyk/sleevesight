package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/**
 * The artist's insight for an artist the sources say nothing about (#912): a web search for the
 * artist, made only for a write and only with *AI web search* used, the app's own (*Once*) or the AI
 * service's while it writes (*Up to 3 times*), its lines kept per artist and found again.
 */
class ArtistWebInsightsTest {
    private val cache = MemoryCache()
    private val names = NameMatcher(JvmAccentFolder)
    private val artistWeb = FakeArtistWeb()

    // *AI web search* as given, Exa web search on.
    private fun webOn(search: AiWebSearch = AiWebSearch.ONCE) =
        Settings(
            aiProvider = ProviderId.ANTHROPIC,
            enabledSources = Settings().enabledSources + SourceSwitch.WEB,
            webEngines = setOf(WebEngine.EXA),
            aiWebSearch = search,
        )

    // As `ArtistWebSource`'s: what a search found kept per artist, none included; a failed one keeps nothing.
    private class FakeArtistWeb(
        var found: List<EvidenceDraft>? = listOf(WEB_LINE),
    ) : ArtistWebLookup {
        val kept = mutableMapOf<ArtistWebQuery, List<EvidenceDraft>>()
        var searches = 0
        val renewed = mutableListOf<ArtistWebQuery>()

        override suspend fun kept(artist: ArtistWebQuery): List<EvidenceDraft>? = kept[artist]

        override suspend fun search(
            artist: ArtistWebQuery,
            sent: () -> Unit,
            mayPay: () -> Boolean,
        ): List<EvidenceDraft>? =
            kept[artist] ?: run {
                if (!mayPay()) return null
                searches++
                sent()
                found?.also { kept[artist] = it }
            }

        override suspend fun keep(
            artist: ArtistWebQuery,
            lines: List<EvidenceDraft>,
        ) {
            kept[artist] = lines
        }

        override suspend fun renew(artist: ArtistWebQuery) {
            renewed += artist
        }
    }

    // The AI's own searches (*Up to 3 times*), each answered with [answer].
    private class Searches(
        private val answer: (String) -> WebSearchOutcome,
    ) : WebSearcher {
        val queries = mutableListOf<String>()

        override suspend fun search(query: String): WebSearchOutcome {
            queries += query
            return answer(query)
        }
    }

    private fun items(
        provider: FakeProvider,
        web: WebSearcher? = null,
    ) = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, artistWeb = artistWeb, web = web)

    // A track of Gehra Hua: a line about the song, and, with [artistLines], one about the artist.
    private fun track(
        title: String = "Gehra Hua",
        artistLines: List<EvidenceDraft> = emptyList(),
    ): TrackState {
        val playing =
            NowPlaying(
                title = title,
                artists = "Shashwat Sachdev",
                album = "Dhurandhar",
                player = "p",
                duration = 360.seconds,
            )
        val drafts = listOf(line(SourceId.MUSICBRAINZ, "$title is a song from Dhurandhar.")) + artistLines
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(SourceId.MUSICBRAINZ to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false)),
            resolution =
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = "rec-$title",
                    artists = listOf(ResolvedArtist("Shashwat Sachdev", "mb-sachdev")),
                ),
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + drafts, JvmHasher),
        )
    }

    private val written = FakeProvider { FakeProvider.done("Shashwat Sachdev is an Indian film composer [S1].") }

    @Test
    fun `once, an artist the sources miss is searched for by its write only, and its insight found again`() =
        runTest {
            val items = items(written)
            val first = track()
            val settings = webOn()
            // Gathered for the row, its key looked up: no search.
            val gathered = checkNotNull(items.evidence(InsightItem.ARTIST, first, settings))
            assertTrue(gathered.webDue)
            assertTrue(gathered.evidence.isEmpty())
            val before = checkNotNull(items.key(InsightItem.ARTIST, first, gathered, settings))
            assertNull(items.kept(before, gathered))
            assertFalse(items.searchesItself(InsightItem.ARTIST, gathered, settings))
            assertEquals(0, artistWeb.searches)

            // The write's search: the web's lines are the artist's, under another key, its request told.
            var sent = 0
            val searched = items.search(InsightItem.ARTIST, first, settings, gathered, sent = { sent++ })
            val found = assertIs<ItemSearch.Found>(searched).evidence
            assertEquals(1, artistWeb.searches)
            assertEquals(1, sent)
            assertEquals(listOf("S1"), found.evidence.ids)
            assertEquals(
                Scope.ARTIST,
                found.evidence.lines
                    .single()
                    .scope,
            )
            assertFalse(found.webDue)
            assertEquals(ArtistWebQuery("Shashwat Sachdev", "mb-sachdev"), artistWeb.kept.keys.single())
            val key = checkNotNull(items.key(InsightItem.ARTIST, first, found, settings))
            assertNotEquals(before, key)
            assertIs<InsightState.Written>(items.write(key, InsightItem.ARTIST, first, found) { _, _ -> })
            assertTrue(WEB_LINE.text in written.requests.single().prompt)
            // Its web lines kept again with the card written from them.
            assertEquals(1, artistWeb.renewed.size)

            // The next track of the artist gathers the kept lines, with no search, and finds the insight.
            val next = track("Jaiye Sajana")
            val again = checkNotNull(items.evidence(InsightItem.ARTIST, next, settings))
            assertFalse(again.webDue)
            assertEquals(key, items.key(InsightItem.ARTIST, next, again, settings))
            assertNotNull(items.kept(key, again))
            assertSame(ItemSearch.None, items.search(InsightItem.ARTIST, next, settings, again))
            assertEquals(1, artistWeb.searches)
            assertEquals(1, written.requests.size)
        }

    @Test
    fun `an artist with a line from the sources is never searched for`() =
        runTest {
            val items = items(written)
            val bio =
                line(SourceId.LASTFM, "The Last.fm biography of Shashwat Sachdev says: \"A composer.\"", Scope.ARTIST)
            val track = track(artistLines = listOf(bio))
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, track, webOn()))
            assertFalse(evidence.webDue)
            assertEquals(listOf("L1"), evidence.evidence.ids)
            assertSame(ItemSearch.None, items.search(InsightItem.ARTIST, track, webOn(), evidence))
            assertEquals(0, artistWeb.searches)
            assertTrue(artistWeb.kept.isEmpty())
        }

    @Test
    fun `no search with AI web search off, or Exa web search off`() =
        runTest {
            val items = items(written)
            val track = track()
            val off = webOn(AiWebSearch.OFF)
            val noExa = webOn().copy(enabledSources = webOn().enabledSources - SourceSwitch.WEB)
            for (settings in listOf(off, noExa)) {
                val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, track, settings))
                assertFalse(evidence.webDue)
                assertSame(ItemSearch.None, items.search(InsightItem.ARTIST, track, settings, evidence))
                // As before: nothing to write from, no call.
                val key = checkNotNull(items.key(InsightItem.ARTIST, track, evidence, settings))
                assertEquals(InsightState.NoEvidence, items.write(key, InsightItem.ARTIST, track, evidence) { _, _ -> })
            }
            assertEquals(0, artistWeb.searches)
            assertTrue(written.requests.isEmpty())
        }

    @Test
    fun `a search that fails keeps nothing, and is due again at the next write`() =
        runTest {
            artistWeb.found = null
            val items = items(written)
            val track = track()
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, track, webOn()))
            assertSame(ItemSearch.Failed, items.search(InsightItem.ARTIST, track, webOn(), evidence))
            assertTrue(artistWeb.kept.isEmpty())
            assertTrue(checkNotNull(items.evidence(InsightItem.ARTIST, track, webOn())).webDue)
            // The next write searches again, and keeps what it finds.
            artistWeb.found = listOf(WEB_LINE)
            assertIs<ItemSearch.Found>(items.search(InsightItem.ARTIST, track, webOn(), evidence))
            assertEquals(2, artistWeb.searches)
        }

    @Test
    fun `once, a gate closed after the lookup's waits makes no search, refused rather than failed`() =
        runTest {
            val items = items(written)
            val track = track()
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, track, webOn()))
            var sent = 0
            val search = items.search(InsightItem.ARTIST, track, webOn(), evidence, { sent++ }) { false }
            assertSame(ItemSearch.Refused, search)
            assertEquals(0, artistWeb.searches)
            assertEquals(0, sent)
            assertTrue(artistWeb.kept.isEmpty())
            assertTrue(checkNotNull(items.evidence(InsightItem.ARTIST, track, webOn())).webDue)
        }

    @Test
    fun `a search that finds nothing is kept so, and the artist is not searched for again`() =
        runTest {
            artistWeb.found = emptyList()
            val items = items(written)
            val track = track()
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, track, webOn()))
            val found = assertIs<ItemSearch.Found>(items.search(InsightItem.ARTIST, track, webOn(), evidence)).evidence
            assertTrue(found.evidence.isEmpty())
            assertFalse(found.webDue)
            val key = checkNotNull(items.key(InsightItem.ARTIST, track, found, webOn()))
            assertEquals(InsightState.NoEvidence, items.write(key, InsightItem.ARTIST, track, found) { _, _ -> })
            // Gathered again: "found nothing" kept, no search due, none made.
            val again = checkNotNull(items.evidence(InsightItem.ARTIST, track("Jaiye Sajana"), webOn()))
            assertFalse(again.webDue)
            assertEquals(1, artistWeb.searches)
            assertTrue(written.requests.isEmpty())
        }

    // A model that searches [query] once, then writes [insight].
    private fun searching(
        insight: String,
        query: String = "Shashwat Sachdev composer",
    ) = FakeProvider(usesTools = true) { request ->
        if (request.toolsAllowed && request.exchanges.isEmpty()) {
            listOf(
                CompletionEvent.ToolUse(
                    ToolTurn(listOf(ToolCall("c1", WebPrompt.TOOL_NAME, """{"query":"$query"}""")), "[]"),
                ),
                CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
            )
        } else {
            FakeProvider.done(insight)
        }
    }

    private val upTo = webOn(AiWebSearch.UP_TO)

    // The artist's insight on [track] written while the AI service searches with [engine], as the model
    // [provider] does, each search and call counted to [counted].
    private suspend fun writtenSearching(
        provider: FakeProvider,
        engine: WebSearcher,
        track: TrackState,
        counted: (QuestionCost) -> Unit = {},
    ): Pair<CardKey, SearchedWrite> {
        val items = items(provider, engine)
        val gathered = checkNotNull(items.evidence(InsightItem.ARTIST, track, upTo))
        assertTrue(gathered.webDue)
        assertTrue(items.searchesItself(InsightItem.ARTIST, gathered, upTo))
        // The app makes no search of its own: the write does.
        assertSame(ItemSearch.None, items.search(InsightItem.ARTIST, track, upTo, gathered))
        val first = checkNotNull(items.key(InsightItem.ARTIST, track, gathered, upTo))
        return first to checkNotNull(items.writeSearching(first, InsightItem.ARTIST, track, upTo, gathered, counted))
    }

    @Test
    fun `up to 3 times, the AI service searches while it writes, and the pages it found are kept as the artist's`() =
        runTest {
            val provider = searching("Shashwat Sachdev scored Uri: The Surgical Strike [S1].")
            val engine = Searches { WebSearchOutcome.Found(listOf(ABOUT, ELSEWHERE)) }
            val counted = mutableListOf<QuestionCost>()
            val (first, result) = writtenSearching(provider, engine, track()) { counted += it }
            assertEquals(listOf("Shashwat Sachdev composer"), engine.queries)
            assertEquals(QuestionCost(searches = 1, calls = 2), counted.fold(QuestionCost(), QuestionCost::plus))
            // The page that names the artist, numbered S1, given to the model and kept; not the other.
            val tool =
                provider.requests[1]
                    .exchanges
                    .single()
                    .results
                    .single()
                    .content
            assertTrue("[S1]" in tool && ABOUT.text in tool)
            assertFalse(ELSEWHERE.text in tool)
            val lines = result.evidence.evidence.lines
            assertEquals(listOf(ABOUT.url to Scope.ARTIST), lines.map { it.url to it.scope })
            assertFalse(result.evidence.webDue)
            assertEquals(
                listOf(ABOUT.url),
                artistWeb.kept.values
                    .flatten()
                    .map { it.url },
            )
            // Checked against those lines, kept under the key they give, with its lines kept again.
            val state = assertIs<InsightState.Written>(result.state)
            assertTrue(state.grounded.citesSource)
            assertEquals(
                listOf(listOf("S1")),
                state.grounded.paragraphs
                    .flatten()
                    .map { it.cites },
            )
            assertNotEquals(first, result.key)
            assertNotNull(cache.cards[result.key])
            assertEquals(1, artistWeb.renewed.size)
        }

    @Test
    fun `up to 3 times, the next track of the artist finds the pages kept and the insight with no search`() =
        runTest {
            val provider = searching("Shashwat Sachdev scored Uri: The Surgical Strike [S1].")
            val engine = Searches { WebSearchOutcome.Found(listOf(ABOUT)) }
            val (_, result) = writtenSearching(provider, engine, track())
            val items = items(provider, engine)
            val next = track("Jaiye Sajana")
            val again = checkNotNull(items.evidence(InsightItem.ARTIST, next, upTo))
            assertFalse(again.webDue)
            assertFalse(items.searchesItself(InsightItem.ARTIST, again, upTo))
            assertEquals(result.key, items.key(InsightItem.ARTIST, next, again, upTo))
            assertNotNull(items.kept(result.key, again))
            assertEquals(1, engine.queries.size)
            assertEquals(2, provider.requests.size)
        }

    @Test
    fun `up to 3 times with a service that takes no tools is the app's own search`() =
        runTest {
            val engine = Searches { WebSearchOutcome.Found(listOf(ABOUT)) }
            val items = items(written, engine)
            val track = track()
            val settings = webOn(AiWebSearch.UP_TO)
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, track, settings))
            assertFalse(items.searchesItself(InsightItem.ARTIST, evidence, settings))
            assertIs<ItemSearch.Found>(items.search(InsightItem.ARTIST, track, settings, evidence))
            assertEquals(1, artistWeb.searches)
            assertTrue(engine.queries.isEmpty())
        }

    @Test
    fun `up to 3 times, searches that all fail keep nothing, and the write fails`() =
        runTest {
            val provider = searching("Shashwat Sachdev is a composer [S1].")
            val items = items(provider, Searches { WebSearchOutcome.Failed })
            val track = track()
            val settings = webOn(AiWebSearch.UP_TO)
            val gathered = checkNotNull(items.evidence(InsightItem.ARTIST, track, settings))
            val first = checkNotNull(items.key(InsightItem.ARTIST, track, gathered, settings))
            val result = checkNotNull(items.writeSearching(first, InsightItem.ARTIST, track, settings, gathered))
            assertEquals(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), result.state)
            assertSame(gathered, result.evidence)
            assertEquals(first, result.key)
            assertTrue(artistWeb.kept.isEmpty())
            assertTrue(cache.cards.isEmpty())
            assertTrue(checkNotNull(items.evidence(InsightItem.ARTIST, track, settings)).webDue)
        }

    @Test
    fun `up to 3 times, searches that find nothing about the artist keep found nothing`() =
        runTest {
            val provider = searching(GroundingCheck.NO_EVIDENCE)
            val items = items(provider, Searches { WebSearchOutcome.Found(listOf(ELSEWHERE)) })
            val track = track()
            val settings = webOn(AiWebSearch.UP_TO)
            val gathered = checkNotNull(items.evidence(InsightItem.ARTIST, track, settings))
            val first = checkNotNull(items.key(InsightItem.ARTIST, track, gathered, settings))
            val result = checkNotNull(items.writeSearching(first, InsightItem.ARTIST, track, settings, gathered))
            assertEquals(InsightState.NoEvidence, result.state)
            assertEquals(emptyList(), artistWeb.kept.values.single())
            assertFalse(checkNotNull(items.evidence(InsightItem.ARTIST, track, settings)).webDue)
        }

    @Test
    fun `up to 3 times, a gate closed while the first call is under way makes no search or call after it`() =
        runTest {
            // #912's review: the app left, or web search switched off, during the model's first turn.
            var open = true
            val provider =
                FakeProvider(usesTools = true) { request ->
                    open = false
                    searching("unused").reply(request)
                }
            val engine = Searches { WebSearchOutcome.Found(listOf(ABOUT)) }
            val items = items(provider, engine)
            val track = track()
            val gathered = checkNotNull(items.evidence(InsightItem.ARTIST, track, upTo))
            val first = checkNotNull(items.key(InsightItem.ARTIST, track, gathered, upTo))
            val counted = mutableListOf<QuestionCost>()
            val gates = WriteGates(mayPay = { open })
            val result =
                items.writeSearching(first, InsightItem.ARTIST, track, upTo, gathered, { counted += it }, null, gates)
            assertNull(result)
            assertTrue(engine.queries.isEmpty())
            assertEquals(1, provider.requests.size)
            assertEquals(QuestionCost(calls = 1), counted.fold(QuestionCost(), QuestionCost::plus))
            assertTrue(artistWeb.kept.isEmpty())
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun `up to 3 times, the key its pages give is reserved before they are kept, and refused keeps nothing`() =
        runTest {
            val provider = searching("Shashwat Sachdev scored Uri: The Surgical Strike [S1].")
            val engine = Searches { WebSearchOutcome.Found(listOf(ABOUT)) }
            val items = items(provider, engine)
            val track = track()
            val gathered = checkNotNull(items.evidence(InsightItem.ARTIST, track, upTo))
            val first = checkNotNull(items.key(InsightItem.ARTIST, track, gathered, upTo))
            val reserved = mutableListOf<CardKey>()
            val accepting =
                WriteGates(
                    reserve = { key ->
                        // Nothing kept yet: another track gathering now finds no pages, or finds the key claimed.
                        assertTrue(artistWeb.kept.isEmpty())
                        assertTrue(cache.cards.isEmpty())
                        reserved += key
                        true
                    },
                )
            val result =
                checkNotNull(items.writeSearching(first, InsightItem.ARTIST, track, upTo, gathered, gates = accepting))
            assertEquals(listOf(result.key), reserved)
            assertNotNull(cache.cards[result.key])

            // Another write of that key under way: nothing kept, no card.
            artistWeb.kept.clear()
            cache.cards.clear()
            val refusing = WriteGates(reserve = { false })
            assertNull(items.writeSearching(first, InsightItem.ARTIST, track, upTo, gathered, gates = refusing))
            assertTrue(artistWeb.kept.isEmpty())
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun `up to 3 times, a reply of no evidence with no search made keeps found nothing, so no loop runs again`() =
        runTest {
            // The model answers at once, searching nothing.
            val provider = FakeProvider(usesTools = true) { FakeProvider.done(GroundingCheck.NO_EVIDENCE) }
            val engine = Searches { WebSearchOutcome.Found(listOf(ABOUT)) }
            val items = items(provider, engine)
            val track = track()
            val gathered = checkNotNull(items.evidence(InsightItem.ARTIST, track, upTo))
            val first = checkNotNull(items.key(InsightItem.ARTIST, track, gathered, upTo))
            val result = checkNotNull(items.writeSearching(first, InsightItem.ARTIST, track, upTo, gathered))
            assertEquals(InsightState.NoEvidence, result.state)
            assertTrue(engine.queries.isEmpty())
            assertFalse(result.evidence.webDue)
            assertEquals(first, result.key)
            // "Found nothing" kept for the artist, as a search that found none keeps it.
            assertEquals(listOf(emptyList<EvidenceDraft>()), artistWeb.kept.values.toList())
            assertNotNull(cache.cards[first])

            // Gathered again: no search due, nothing to write from, found kept with no call.
            val again = checkNotNull(items.evidence(InsightItem.ARTIST, track("Jaiye Sajana"), upTo))
            assertFalse(again.webDue)
            assertFalse(items.searchesItself(InsightItem.ARTIST, again, upTo))
            assertEquals(InsightState.NoEvidence, items.kept(first, again)?.state)
            assertEquals(1, provider.requests.size)

            // A reply that is not `NO_EVIDENCE`, with no search, keeps nothing: still due.
            artistWeb.kept.clear()
            val knowing = FakeProvider(usesTools = true) { FakeProvider.done("He is a composer [S1].") }
            val other = items(knowing, engine)
            val written = checkNotNull(other.writeSearching(first, InsightItem.ARTIST, track, upTo, gathered))
            assertTrue(written.evidence.webDue)
            assertTrue(artistWeb.kept.isEmpty())
        }

    private companion object {
        val WEB_LINE =
            EvidenceDraft(
                SourceId.WEB,
                Scope.ARTIST,
                "Shashwat Sachdev is an Indian film composer and music producer.",
                "https://example.com/sachdev",
                RETRIEVED,
            )

        val ABOUT =
            WebPage(
                "https://example.com/composer",
                "Shashwat Sachdev",
                "Shashwat Sachdev won the National Film Award for the score of Uri: The Surgical Strike.",
            )

        val ELSEWHERE = WebPage("https://example.com/film", "Dhurandhar", "Dhurandhar is a 2025 action film.")
    }
}
