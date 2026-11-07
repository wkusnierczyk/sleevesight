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
        album: String? = "Getaway",
    ): TrackState {
        val playing =
            NowPlaying(
                title = "Phil's First Tear",
                artists = "Kolonel Djafaar",
                album = album,
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
    fun `an artist's tags and counts never reach the song's insight, so reordered tags keep its key`() =
        runTest {
            fun tags(list: String) = aggregate("Last.fm listeners tag Kolonel Djafaar as: $list.")
            val items = items(plain)
            val off = webOn(AiWebSearch.OFF)
            val listeners = aggregate("Last.fm counts 900 listeners for Kolonel Djafaar.")
            val one = track(artist = listOf(listeners, tags("afrobeat, ethio-jazz")))
            val two = track(artist = listOf(listeners, tags("ethio-jazz, afrobeat")))
            val first = checkNotNull(items.evidence(InsightItem.SONG, one, off))
            val second = checkNotNull(items.evidence(InsightItem.SONG, two, off))
            assertTrue(first.onlyListings)
            assertTrue(first.evidence.lines.none { it.scope == Scope.ARTIST })
            assertEquals(items.key(InsightItem.SONG, one, first, off), items.key(InsightItem.SONG, two, second, off))
        }

    @Test
    fun `withoutCiting takes out the sentences that cite an id, and leaves a reply with none as it came`() {
        val reply = "One  [S1]. Two [W1].\n\nThree [S1][W1].\nFour [S2]."
        assertEquals("One [S1].\n\nFour [S2].", withoutCiting(reply, setOf("W1")))
        assertEquals(reply, withoutCiting(reply, setOf("W9")))
        assertEquals(reply, withoutCiting(reply, emptySet()))
        assertEquals("", withoutCiting("Two [W1].", setOf("W1")))
        assertEquals(GroundingCheck.NO_EVIDENCE, withoutCiting(GroundingCheck.NO_EVIDENCE, setOf("W1")))
        val plain = "${GroundingCheck.ONLY_RELEASE_SENTENCE} One [S1]."
        assertEquals(plain, withoutCiting(plain, emptySet()))
        assertEquals("One [S1].", withoutCiting(plain, emptySet(), described = true))
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

    // [reply] written under *Up to 3 times* for the track, its search finding [PAGE], a description.
    private suspend fun writtenFromDescription(reply: String): Pair<ItemInsights, SearchedWrite> {
        val items = items(searching(reply), Searches { WebSearchOutcome.Found(listOf(PAGE)) })
        val upTo = webOn(AiWebSearch.UP_TO)
        val evidence = checkNotNull(items.evidence(InsightItem.SONG, track(), upTo))
        val key = checkNotNull(items.key(InsightItem.SONG, track(), evidence, upTo))
        return items to checkNotNull(items.writeSearching(key, InsightItem.SONG, track(), upTo, evidence))
    }

    private fun artistId(): String =
        checkNotNull(track().evidence)
            .lines
            .single { it.scope == Scope.ARTIST }
            .id

    @Test
    fun `up to 3 times, a description found, a sentence citing the artist's line goes, fresh and kept`() =
        runTest {
            val reply = "Its horns are heavy [S1]. The band plays Afrobeat [${artistId()}]. It is slow [S1]."
            val (items, result) = writtenFromDescription(reply)
            assertFalse(result.evidence.onlyListings)
            val fresh = assertIs<InsightState.Written>(result.state)
            assertEquals("Its horns are heavy [S1]. It is slow [S1].", fresh.grounded.text)
            assertEquals(0, fresh.grounded.counts.unknown)
            assertEquals("Its horns are heavy [S1]. It is slow [S1].", cache.cards[result.key])
            val kept = assertIs<InsightState.Written>(checkNotNull(items.kept(result.key, result.evidence)).state)
            assertEquals(0, kept.grounded.counts.unknown)
            assertEquals(fresh.grounded.text, kept.grounded.text)
        }

    @Test
    fun `up to 3 times, a description found, nothing left once they go, the write fails and the pages stay`() =
        runTest {
            val (_, result) = writtenFromDescription("The band plays Afrobeat [${artistId()}].")
            assertIs<InsightState.Failed>(result.state)
            assertTrue(cache.cards.isEmpty())
            assertEquals(
                listOf(PAGE.url),
                songWeb.kept.values
                    .flatten()
                    .map { it.url },
            )
        }

    @Test
    fun `up to 3 times, no description found, the artist's sentence stays`() =
        runTest {
            val reply = "${GroundingCheck.ONLY_RELEASE_SENTENCE} The band plays Afrobeat [${artistId()}]."
            val items = items(searching(reply), Searches { WebSearchOutcome.Found(emptyList()) })
            val upTo = webOn(AiWebSearch.UP_TO)
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, track(), upTo))
            val key = checkNotNull(items.key(InsightItem.SONG, track(), evidence, upTo))
            val result = checkNotNull(items.writeSearching(key, InsightItem.SONG, track(), upTo, evidence))
            assertEquals(reply, assertIs<InsightState.Written>(result.state).grounded.text)
            assertEquals(reply, cache.cards[result.key])
        }

    @Test
    fun `no line at all but the artist's, no album, stays nothing to write from, with no call`() =
        runTest {
            val items = items(plain)
            val bare = track(own = emptyList(), album = null)
            val off = webOn(AiWebSearch.OFF)
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, bare, off))
            assertTrue(evidence.evidence.isEmpty())
            assertFalse(evidence.onlyListings)
            assertFalse(evidence.webDue)
            val key = checkNotNull(items.key(InsightItem.SONG, bare, evidence, off))
            assertEquals(ItemPrompt.VERSION, key.promptVersion)
            assertSame(InsightState.NoEvidence, items.write(key, InsightItem.SONG, bare, evidence) { _, _ -> })
            assertTrue(plain.requests.isEmpty())

            // A search can still be due for it; found nothing, the same.
            songWeb.found = emptyList()
            val settings = webOn()
            val due = checkNotNull(items.evidence(InsightItem.SONG, bare, settings))
            assertTrue(due.webDue)
            val found = assertIs<ItemSearch.Found>(items.search(InsightItem.SONG, bare, settings, due)).evidence
            assertTrue(found.evidence.isEmpty())
            assertFalse(found.onlyListings)
            val after = checkNotNull(items.key(InsightItem.SONG, bare, found, settings))
            assertSame(InsightState.NoEvidence, items.write(after, InsightItem.SONG, bare, found) { _, _ -> })
            assertTrue(plain.requests.isEmpty())
        }

    @Test
    fun `up to 3 times, a thin page read as a description, the plain sentence and the artist's go`() =
        runTest {
            val reply =
                "${GroundingCheck.ONLY_RELEASE_SENTENCE} The band plays Afrobeat [${artistId()}]. " +
                    "It came out in 2024 [MB1]."
            val (_, result) = writtenFromDescription(reply)
            val state = assertIs<InsightState.Written>(result.state)
            assertEquals("It came out in 2024 [MB1].", state.grounded.text)
            assertEquals("It came out in 2024 [MB1].", cache.cards[result.key])
        }

    @Test
    fun `up to 3 times, a description found, the plain sentence alone leaves nothing, and the write fails`() =
        runTest {
            val (_, result) = writtenFromDescription(GroundingCheck.ONLY_RELEASE_SENTENCE)
            assertIs<InsightState.Failed>(result.state)
            assertTrue(cache.cards.isEmpty())
            assertEquals(
                listOf(PAGE.url),
                songWeb.kept.values
                    .flatten()
                    .map { it.url },
            )
        }

    @Test
    fun `up to 3 times, a complete reply with no search made keeps found nothing, so no search stays due`() =
        runTest {
            val provider = searching("It came out in 2024 [MB1].", searches = 0)
            val engine = Searches { WebSearchOutcome.Found(emptyList()) }
            val items = items(provider, engine)
            val upTo = webOn(AiWebSearch.UP_TO)
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, track(), upTo))
            val key = checkNotNull(items.key(InsightItem.SONG, track(), evidence, upTo))
            val result = checkNotNull(items.writeSearching(key, InsightItem.SONG, track(), upTo, evidence))
            assertTrue(engine.queries.isEmpty())
            assertEquals(key, result.key)
            assertFalse(result.evidence.webDue)
            assertEquals(emptyList(), songWeb.kept.values.single())
            val again = checkNotNull(items.evidence(InsightItem.SONG, track(), upTo))
            assertFalse(again.webDue)
            assertFalse(items.searchesItself(InsightItem.SONG, again, upTo))
            assertEquals(key, items.key(InsightItem.SONG, track(), again, upTo))
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
