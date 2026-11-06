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
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SongWebLookup
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.ItemPrompt
import app.sleevesight.core.synthesis.SentenceStatus
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
import kotlin.time.Instant

/**
 * The song's insight for a song the sources only list (#958): a web search for it, made only for a
 * write and only with *AI web search* used; what it found kept per track, "found nothing" too; and,
 * with no description found, the fixed sentence, the artist's lines in the prompt for that case alone.
 */
class SongWebInsightsTest {
    private val cache = MemoryCache()
    private val names = NameMatcher(JvmAccentFolder)
    private val songWeb = FakeSongWeb()

    private fun webOn(search: AiWebSearch = AiWebSearch.ONCE) =
        Settings(
            aiProvider = ProviderId.ANTHROPIC,
            enabledSources = Settings().enabledSources + SourceSwitch.WEB,
            webEngines = setOf(WebEngine.EXA),
            aiWebSearch = search,
        )

    // As `SongWebSource`'s: what a search found kept per track, none included; a failed one keeps nothing.
    private class FakeSongWeb(
        var found: List<EvidenceDraft>? = listOf(DESCRIPTION),
    ) : SongWebLookup {
        val kept = mutableMapOf<String, List<EvidenceDraft>>()
        val queries = mutableListOf<SongWebQuery>()
        var searches = 0
        var renewed = 0

        override suspend fun kept(song: SongWebQuery): List<EvidenceDraft>? = kept[song.track.lookupKey]

        override suspend fun search(
            song: SongWebQuery,
            sent: () -> Unit,
            keeping: (List<EvidenceDraft>) -> Unit,
            mayPay: () -> Boolean,
        ): List<EvidenceDraft>? =
            kept[song.track.lookupKey] ?: run {
                if (!mayPay()) return null
                searches++
                queries += song
                sent()
                found?.also {
                    keeping(it)
                    kept[song.track.lookupKey] = it
                }
            }

        override suspend fun keep(
            song: SongWebQuery,
            lines: List<EvidenceDraft>,
        ) {
            kept[song.track.lookupKey] = lines
        }

        override suspend fun pageLines(song: SongWebQuery): (WebPage, Instant) -> EvidenceDraft? =
            { page, at ->
                queries += song
                EvidenceDraft(SourceId.WEB, Scope.WEB, page.text, page.url, at).takeIf { "elsewhere" !in page.url }
            }

        override suspend fun renew(song: SongWebQuery) {
            renewed++
        }
    }

    private class Searches(
        private val answer: (String) -> WebSearchOutcome,
    ) : WebSearcher {
        val queries = mutableListOf<String>()

        override suspend fun search(
            query: String,
            subject: List<String>?,
        ): WebSearchOutcome {
            queries += query
            return answer(query)
        }
    }

    private fun items(
        provider: FakeProvider,
        web: WebSearcher? = null,
    ) = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, web = web, songWeb = songWeb)

    // Phil's First Tear, with [own] lines about the song and a Wikipedia line about the artist.
    private fun track(
        own: List<EvidenceDraft> = listOf(RELEASE_LINE),
        artist: List<EvidenceDraft> = listOf(ARTIST_LINE),
        inLanguage: InLanguageState? = null,
        others: Map<SourceId, SourceState> = emptyMap(),
    ): TrackState {
        val playing =
            NowPlaying(
                title = "Phil's First Tear",
                artists = "Kolonel Djafaar",
                album = "Getaway",
                player = "p",
                duration = 300.seconds,
            )
        val drafts = own + artist
        val bySource =
            drafts.groupBy { it.source }.mapValues { (_, lines) ->
                SourceState.Done(SourceResult(Outcome.SongArticle, lines, ZERO), false)
            }
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources = bySource + others,
            resolution = Resolution(outcome = Outcome.SongArticle, recordingMbid = "rec"),
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + drafts, JvmHasher),
            inLanguage = inLanguage,
        )
    }

    private val written = FakeProvider { FakeProvider.done("It is built on a heavy horn line [S1].") }

    private val plain =
        FakeProvider {
            FakeProvider.done(
                "${GroundingCheck.ONLY_RELEASE_SENTENCE} Kolonel Djafaar's sound is Afrobeat and Ethio-jazz [W1]. " +
                    "It came out in 2024 [MB1].",
            )
        }

    @Test
    fun `once, a song the sources only list is searched for by its write only, and its insight found again`() =
        runTest {
            val items = items(written)
            val first = track()
            val settings = webOn()
            val gathered = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            assertTrue(gathered.webDue)
            assertTrue(items.searchesWeb(InsightItem.SONG, settings))
            val before = checkNotNull(items.key(InsightItem.SONG, first, gathered, settings))
            assertNull(items.kept(before, gathered))
            assertEquals(0, songWeb.searches)

            var sent = 0
            val found =
                assertIs<ItemSearch.Found>(
                    items.search(InsightItem.SONG, first, settings, gathered, sent = { sent++ }),
                ).evidence
            assertEquals(1, songWeb.searches)
            assertEquals(1, sent)
            assertFalse(found.webDue)
            assertFalse(found.onlyListings)
            // It describes the song, so the usual prompt: no artist's lines, its own version.
            assertTrue(found.evidence.lines.none { it.scope == Scope.ARTIST })
            assertEquals(
                Scope.WEB,
                found.evidence.lines
                    .single { it.source == SourceId.WEB }
                    .scope,
            )
            val key = checkNotNull(items.key(InsightItem.SONG, first, found, settings))
            assertNotEquals(before, key)
            assertEquals(ItemPrompt.VERSION, key.promptVersion)
            assertIs<InsightState.Written>(items.write(key, InsightItem.SONG, first, found) { _, _ -> })
            val prompt = written.requests.single().prompt
            assertTrue(DESCRIPTION.text in prompt)
            assertFalse(GroundingCheck.ONLY_RELEASE_SENTENCE in prompt)
            assertEquals(1, songWeb.renewed)

            // A later gathering holds the lines and finds the insight, with no search or call.
            val again = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            assertFalse(again.webDue)
            assertEquals(key, items.key(InsightItem.SONG, first, again, settings))
            assertNotNull(items.kept(key, again))
            assertEquals(1, songWeb.searches)
            assertEquals(1, written.requests.size)
        }

    @Test
    fun `a song with a description sets off no search, and its key and prompt are as before`() =
        runTest {
            val items = items(written)
            val own = EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "A slow groove.", "https://w.example", RETRIEVED)
            val described = track(own = listOf(RELEASE_LINE, own))
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, described, webOn()))
            assertFalse(evidence.webDue)
            assertFalse(evidence.onlyListings)
            assertTrue(evidence.evidence.lines.none { it.scope == Scope.ARTIST })
            val whole = checkNotNull(described.evidence)
            assertEquals(
                whole.without(JvmHasher) { it.scope == Scope.ARTIST }.fingerprint,
                evidence.evidence.fingerprint,
            )
            assertEquals(
                ItemPrompt.VERSION,
                checkNotNull(items.key(InsightItem.SONG, described, evidence, webOn())).promptVersion,
            )
            assertSame(ItemSearch.None, items.search(InsightItem.SONG, described, webOn(), evidence))
            assertEquals(0, songWeb.searches)
        }

    @Test
    fun `off, none is searched for, and the prompt says what it may write, with the artist's lines`() =
        runTest {
            val items = items(plain)
            val off = webOn(AiWebSearch.OFF)
            val first = track()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, off))
            assertFalse(evidence.webDue)
            assertTrue(evidence.onlyListings)
            assertFalse(items.searchesWeb(InsightItem.SONG, off))
            assertSame(ItemSearch.None, items.search(InsightItem.SONG, first, off, evidence))
            assertTrue(evidence.evidence.lines.any { it.scope == Scope.ARTIST })
            // The artist's line keeps the track's id, mapped to the line shown.
            val artist = evidence.evidence.lines.single { it.scope == Scope.ARTIST }
            assertEquals(checkNotNull(first.evidence).lines.single { it.scope == Scope.ARTIST }.id, artist.id)
            assertEquals(artist.id, evidence.trackIds[artist.id])
            val key = checkNotNull(items.key(InsightItem.SONG, first, evidence, off))
            assertEquals(ItemPrompt.ONLY_LISTINGS_VERSION, key.promptVersion)
            val state = assertIs<InsightState.Written>(items.write(key, InsightItem.SONG, first, evidence) { _, _ -> })
            val prompt = plain.requests.single().prompt
            assertTrue(ItemPrompt.ONLY_LISTINGS_RULE in prompt)
            assertFalse(ItemPrompt.GAP_RULE in prompt)
            assertTrue(ARTIST_LINE.text in prompt)
            assertEquals(0, songWeb.searches)
            // The fixed sentence is a hedge, the others pass: nothing dimmed.
            val sentences = state.grounded.paragraphs.flatten()
            assertEquals(SentenceStatus.HEDGE, sentences.first().status)
            assertEquals(listOf(SentenceStatus.OK, SentenceStatus.OK), sentences.drop(1).map { it.status })
        }

    @Test
    fun `a reply of the fixed sentence alone counts as written, and is kept`() =
        runTest {
            val only = FakeProvider { FakeProvider.done(GroundingCheck.ONLY_RELEASE_SENTENCE) }
            val items = items(only)
            val off = webOn(AiWebSearch.OFF)
            val first = track()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, off))
            val key = checkNotNull(items.key(InsightItem.SONG, first, evidence, off))
            val state = assertIs<InsightState.Written>(items.write(key, InsightItem.SONG, first, evidence) { _, _ -> })
            assertEquals(
                SentenceStatus.HEDGE,
                state.grounded.paragraphs
                    .single()
                    .single()
                    .status,
            )
            assertEquals(1, only.requests.size)
            assertNotNull(items.kept(key, evidence))
        }

    @Test
    fun `an insight kept from before the search, from the same lines, is not found under the new key`() =
        runTest {
            val items = items(written)
            val first = track()
            val settings = webOn()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            val key = checkNotNull(items.key(InsightItem.SONG, first, evidence, settings))
            val old = key.copy(promptVersion = ItemPrompt.VERSION)
            cache.keep(old, "It came out in 2024 [MB1].", RETRIEVED, emptySet())
            assertNull(items.kept(key, evidence))
        }

    @Test
    fun `once, a failed search keeps nothing, makes no call, and is due again at the next write`() =
        runTest {
            songWeb.found = null
            val items = items(written)
            val first = track()
            val settings = webOn()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            assertSame(ItemSearch.Failed, items.search(InsightItem.SONG, first, settings, evidence))
            assertTrue(songWeb.kept.isEmpty())
            assertTrue(written.requests.isEmpty())
            assertTrue(checkNotNull(items.evidence(InsightItem.SONG, first, settings)).webDue)
            songWeb.found = listOf(DESCRIPTION)
            assertIs<ItemSearch.Found>(items.search(InsightItem.SONG, first, settings, evidence))
            assertEquals(2, songWeb.searches)
        }

    @Test
    fun `once, a closed gate sends nothing and keeps nothing`() =
        runTest {
            val items = items(written)
            val first = track()
            val settings = webOn()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            val refused = items.search(InsightItem.SONG, first, settings, evidence, mayPay = { false })
            assertSame(ItemSearch.Refused, refused)
            assertEquals(0, songWeb.searches)
            assertTrue(songWeb.kept.isEmpty())
        }

    @Test
    fun `found nothing is kept, with no second search, and the insight says so with the artist's lines`() =
        runTest {
            songWeb.found = emptyList()
            val items = items(plain)
            val first = track()
            val settings = webOn()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            val found =
                assertIs<ItemSearch.Found>(items.search(InsightItem.SONG, first, settings, evidence)).evidence
            assertFalse(found.webDue)
            assertTrue(found.onlyListings)
            assertEquals(emptyList(), songWeb.kept.values.single())
            val again = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            assertFalse(again.webDue)
            assertSame(ItemSearch.None, items.search(InsightItem.SONG, first, settings, again))
            assertEquals(1, songWeb.searches)
            assertEquals(
                items.key(InsightItem.SONG, first, found, settings),
                items.key(InsightItem.SONG, first, again, settings),
            )
        }

    @Test
    fun `only listings found, the insight says so, and the pages are among its lines`() =
        runTest {
            songWeb.found = listOf(LISTING)
            val items = items(plain)
            val first = track()
            val settings = webOn()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            val found =
                assertIs<ItemSearch.Found>(items.search(InsightItem.SONG, first, settings, evidence)).evidence
            assertTrue(found.onlyListings)
            assertTrue(found.evidence.lines.any { it.url == LISTING.url })
            assertTrue(found.evidence.lines.any { it.scope == Scope.ARTIST })
        }

    @Test
    fun `an artist with no description gives its tags, and no count or list of similar artists`() =
        runTest {
            val tags = aggregate("Last.fm listeners tag Kolonel Djafaar as: afrobeat, ethio-jazz.")
            val listeners = aggregate("Last.fm counts 900 listeners for Kolonel Djafaar.")
            val items = items(plain)
            val off = webOn(AiWebSearch.OFF)
            val evidence =
                checkNotNull(items.evidence(InsightItem.SONG, track(artist = listOf(listeners, tags)), off))
            assertEquals(
                listOf(tags.text),
                evidence.evidence.lines
                    .filter { it.scope == Scope.ARTIST }
                    .map { it.text },
            )
        }

    @Test
    fun `while a source that may describe the song could not be asked, no search is due, nor the fixed sentence`() =
        runTest {
            val items = items(written)
            val unreachable = SourceState.Done(SourceResult(Outcome.Unreachable, emptyList(), ZERO), false)
            val first = track(others = mapOf(SourceId.WIKIPEDIA to unreachable))
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, webOn()))
            assertFalse(evidence.webDue)
            assertFalse(evidence.onlyListings)
        }

    @Test
    fun `the search goes in the track's language when one is known, and not under Off`() =
        runTest {
            val items = items(written)
            val thai = InLanguageState("th", emptyMap())
            val first = track(inLanguage = thai)
            val settings = webOn().copy(trackLanguageSearch = TrackLanguageSearch.IF_NEEDED)
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, settings))
            items.search(InsightItem.SONG, first, settings, evidence)
            assertEquals(
                "th",
                songWeb.queries
                    .single()
                    .language
                    ?.code,
            )
            songWeb.queries.clear()
            songWeb.kept.clear()
            val english = settings.copy(trackLanguageSearch = TrackLanguageSearch.OFF)
            items.search(InsightItem.SONG, first, english, evidence)
            assertNull(songWeb.queries.single().language)
        }

    // Turns of a model that searches the web [searches] times, then writes [reply].
    private fun searching(
        reply: String,
        searches: Int = 1,
    ): FakeProvider =
        FakeProvider(usesTools = true) { request ->
            val made = request.exchanges.size
            if (request.toolsAllowed && made < searches) {
                val call = ToolCall("c$made", WebPrompt.TOOL_NAME, """{"query":"Phil's First Tear $made"}""")
                listOf(
                    CompletionEvent.ToolUse(ToolTurn(listOf(call), "[]")),
                    CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
                )
            } else {
                FakeProvider.done(reply)
            }
        }

    @Test
    fun `up to 3 times, the AI service searches while it writes, told the language, and its pages are kept`() =
        runTest {
            val provider = searching("It is built on a heavy horn line [S1].")
            val engine = Searches { WebSearchOutcome.Found(listOf(PAGE, ELSEWHERE)) }
            val items = items(provider, engine)
            val upTo = webOn(AiWebSearch.UP_TO)
            val first = track(inLanguage = InLanguageState("th", emptyMap()))
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, upTo))
            assertTrue(items.searchesItself(InsightItem.SONG, evidence, upTo))
            assertSame(ItemSearch.None, items.search(InsightItem.SONG, first, upTo, evidence))
            val key = checkNotNull(items.key(InsightItem.SONG, first, evidence, upTo))
            val cost = mutableListOf<QuestionCost>()
            val result =
                checkNotNull(items.writeSearching(key, InsightItem.SONG, first, upTo, evidence, { cost += it }))
            assertEquals(QuestionCost(searches = 1, calls = 2), cost.fold(QuestionCost(), QuestionCost::plus))
            val firstAsked = provider.requests.first().prompt
            assertTrue("search in Thai" in firstAsked, firstAsked)
            assertTrue(ItemPrompt.ONLY_LISTINGS_RULE in firstAsked)
            val tool =
                provider.requests[1]
                    .exchanges
                    .single()
                    .results
                    .single()
                    .content
            assertTrue("[S1]" in tool && PAGE.text in tool)
            assertFalse(ELSEWHERE.text in tool)
            assertEquals(
                listOf(PAGE.url),
                songWeb.kept.values
                    .flatten()
                    .map { it.url },
            )
            assertFalse(result.evidence.webDue)
            assertFalse(result.evidence.onlyListings)
            assertNotEquals(key, result.key)
            assertEquals(ItemPrompt.VERSION, result.key.promptVersion)
            assertIs<InsightState.Written>(result.state)
            assertNotNull(cache.cards[result.key])
            assertEquals(1, songWeb.renewed)
            // A later gathering finds it, with no search.
            val again = checkNotNull(items.evidence(InsightItem.SONG, first, upTo))
            assertEquals(result.key, items.key(InsightItem.SONG, first, again, upTo))
            assertFalse(items.searchesItself(InsightItem.SONG, again, upTo))
        }

    @Test
    fun `up to 3 times, at most three searches and four calls`() =
        runTest {
            val provider = searching("It came out in 2024 [MB1].", searches = 9)
            val engine = Searches { WebSearchOutcome.Found(emptyList()) }
            val items = items(provider, engine)
            val upTo = webOn(AiWebSearch.UP_TO)
            val first = track()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, upTo))
            val key = checkNotNull(items.key(InsightItem.SONG, first, evidence, upTo))
            val cost = mutableListOf<QuestionCost>()
            items.writeSearching(key, InsightItem.SONG, first, upTo, evidence, { cost += it })
            assertEquals(QuestionCost(searches = 3, calls = 4), cost.fold(QuestionCost(), QuestionCost::plus))
            // Found nothing: kept, no search due, the insight's own key.
            assertEquals(emptyList(), songWeb.kept.values.single())
        }

    @Test
    fun `up to 3 times, searches that all fail keep nothing and fail the write`() =
        runTest {
            val provider = searching("It came out in 2024 [MB1].")
            val items = items(provider, Searches { WebSearchOutcome.Failed })
            val upTo = webOn(AiWebSearch.UP_TO)
            val first = track()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, upTo))
            val key = checkNotNull(items.key(InsightItem.SONG, first, evidence, upTo))
            val result = checkNotNull(items.writeSearching(key, InsightItem.SONG, first, upTo, evidence))
            assertEquals(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), result.state)
            assertTrue(songWeb.kept.isEmpty())
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun `up to 3 times with a service whose client takes no tools, the app's own one search`() =
        runTest {
            val provider = FakeProvider(usesTools = false) { FakeProvider.done("It is slow [S1].") }
            val items = items(provider, Searches { WebSearchOutcome.Found(emptyList()) })
            val upTo = webOn(AiWebSearch.UP_TO)
            val first = track()
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, first, upTo))
            assertFalse(items.searchesItself(InsightItem.SONG, evidence, upTo))
            assertIs<ItemSearch.Found>(items.search(InsightItem.SONG, first, upTo, evidence))
            assertEquals(1, songWeb.searches)
        }

    private companion object {
        val RELEASE_LINE =
            EvidenceDraft(SourceId.MUSICBRAINZ, Scope.SONG, "It was first released on April 5, 2024.", null, RETRIEVED)

        val ARTIST_LINE =
            EvidenceDraft(
                SourceId.WIKIPEDIA,
                Scope.ARTIST,
                "Kolonel Djafaar is an instrumental Afrobeat and Ethio-jazz band.",
                "https://en.wikipedia.org/wiki/Kolonel_Djafaar",
                RETRIEVED,
            )

        val DESCRIPTION =
            EvidenceDraft(
                SourceId.WEB,
                Scope.WEB,
                "The track rides a heavy horn line over a slow groove, cut live in Antwerp.",
                "https://blog.example/phil",
                RETRIEVED,
            )

        val LISTING =
            EvidenceDraft(
                SourceId.WEB,
                Scope.WEB,
                "Stream Phil's First Tear.",
                "https://open.spotify.com/track/1",
                RETRIEVED,
            )

        val PAGE = WebPage("https://blog.example/page", "Phil's First Tear", "A heavy horn line over a slow groove.")

        val ELSEWHERE = WebPage("https://elsewhere.example/page", "Other", "Something else altogether.")

        fun aggregate(text: String) =
            EvidenceDraft(SourceId.LASTFM, Scope.ARTIST, text, "https://last.fm/x", RETRIEVED, aggregate = true)
    }
}
