package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * The check of a track's vocals (#998): only with *AI web search* used; *Once* one app-built search
 * and one call, *Up to 3 times* up to three searches the model writes and four calls, each counted;
 * the answer grounded in a page found, and kept per track, so a replay asks nothing.
 */
class VocalsChecksTest {
    private val cache = MemoryCache()
    private val page = WebPage("https://bandcamp.example/nasnusa", "Nasnusa", "Nasnusa, sung by Yurika Hanashima.")
    private val queries = mutableListOf<String>()
    private var outcome: WebSearchOutcome = WebSearchOutcome.Found(listOf(page))
    private val web =
        WebSearcher { query, _ ->
            queries += query
            outcome
        }
    private val counted = mutableListOf<QuestionCost>()
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC, aiWebSearch = AiWebSearch.ONCE)

    private val track: TrackState =
        run {
            val playing = nowPlaying("Nasnusa", "Sababa 5, Yurika Hanashima", "Kokoro")
            val drafts = listOf(line(SourceId.WIKIPEDIA, "Sababa 5 is a band from Tel Aviv."))
            TrackState(
                nowPlaying = playing,
                normalized = Normalizer(JvmAccentFolder).normalize(playing),
                sources =
                    mapOf(
                        SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                    ),
                evidence = EvidenceSet.of(drafts, JvmHasher),
            )
        }

    private fun checks(provider: FakeProvider) =
        VocalsChecks(AiAccess(provider, Secret("k")), cache, JvmHasher, web = web)

    private fun answer(said: String) = FakeProvider.done("""{"answer": "$said", "source": "${page.url}"}""")

    @Test
    fun `there is no check with AI web search off or no web search engine`() {
        val provider = FakeProvider { answer("vocals") }
        assertNull(checks(provider).key(track, settings.copy(aiWebSearch = AiWebSearch.OFF)))
        assertNull(VocalsChecks(AiAccess(provider, Secret("k")), cache, JvmHasher).key(track, settings))
        assertNull(checks(provider).key(track, settings.copy(writeInsights = false)))
        val key = checkNotNull(checks(provider).key(track, settings))
        assertEquals(CardKind.VOCALS, key.kind)
        assertFalse(key.modelKnowledge)
    }

    @Test
    fun `Once is one search the app builds, with the vocals in view, and one call with its pages`() =
        runTest {
            val provider = FakeProvider { answer("vocals") }
            val checks = checks(provider)
            val key = checkNotNull(checks.key(track, settings))
            val shown = mutableListOf<VocalsState>()
            val end =
                checks.ask(key, track, AiSearches.Asking(AiWebSearch.ONCE), { counted += it }) { s, _ ->
                    shown +=
                        s
                }
            assertEquals(VocalsState.Checked(VocalsAnswer.VOCALS, key.model, page), end)
            assertEquals(listOf(VocalsState.Checking, end), shown)
            assertEquals(
                listOf("\"Nasnusa\" by Sababa 5, Yurika Hanashima, instrumental or vocals, lyrics, featuring"),
                queries,
            )
            val prompt = provider.requests.single().prompt
            assertTrue("Title: Nasnusa" in prompt && "Artists: Sababa 5, Yurika Hanashima" in prompt)
            assertTrue("Album: Kokoro" in prompt && page.url in prompt)
            // Nothing the sources found: the title, artists and album with the pages.
            assertFalse("Tel Aviv" in prompt)
            assertEquals(listOf(QuestionCost.SEARCH, QuestionCost.CALL), counted)
        }

    @Test
    fun `Up to 3 times lets the model write up to three searches, at most four calls`() =
        runTest {
            var turn = 0
            val provider =
                FakeProvider(usesTools = true) {
                    turn++
                    if (turn <= 3) {
                        listOf(
                            CompletionEvent.ToolUse(
                                ToolTurn(
                                    listOf(ToolCall("c$turn", WebPrompt.TOOL_NAME, """{"query":"q$turn"}""")),
                                    "[]",
                                ),
                            ),
                            CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
                        )
                    } else {
                        answer("instrumental")
                    }
                }
            val checks = checks(provider)
            val key = checkNotNull(checks.key(track, settings.copy(aiWebSearch = AiWebSearch.UP_TO)))
            val end = checks.ask(key, track, AiSearches.Asking(AiWebSearch.UP_TO), { counted += it }) { _, _ -> }
            assertEquals(VocalsAnswer.INSTRUMENTAL, assertIs<VocalsState.Checked>(end).answer)
            assertEquals(listOf("q1", "q2", "q3"), queries)
            assertEquals(4, provider.requests.size)
            assertEquals(QuestionCost(searches = 3, calls = 4), counted.reduce(QuestionCost::plus))
        }

    @Test
    fun `an answer with no page among those found, or none, is unknown`() =
        runTest {
            val made =
                FakeProvider { FakeProvider.done("""{"answer": "vocals", "source": "https://elsewhere.example/x"}""") }
            val checks = checks(made)
            val key = checkNotNull(checks.key(track, settings))
            val end = checks.ask(key, track, AiSearches.Asking(AiWebSearch.ONCE)) { _, _ -> }
            assertEquals(VocalsState.Checked(VocalsAnswer.UNKNOWN, key.model), end)
            // No page found: no call, nothing said either way.
            outcome = WebSearchOutcome.Found(emptyList())
            val none = FakeProvider { answer("vocals") }
            val other = checks(none)
            val again = checks(none).key(track.copy(nowPlaying = track.nowPlaying.copy(album = "Other")), settings)
            assertEquals(
                VocalsAnswer.UNKNOWN,
                assertIs<VocalsState.Checked>(other.ask(checkNotNull(again), track) { _, _ -> }).answer,
            )
            assertTrue(none.requests.isEmpty())
        }

    @Test
    fun `an answer is kept per track, so a replay asks nothing, and a failure is not`() =
        runTest {
            val other = WebPage("https://example.org/sababa-5", "Sababa 5", "A band from Tel Aviv.")
            outcome = WebSearchOutcome.Found(listOf(other, page))
            val provider = FakeProvider { answer("instrumental") }
            val checks = checks(provider)
            val key = checkNotNull(checks.key(track, settings))
            assertNull(checks.kept(key))
            checks.ask(key, track, AiSearches.Asking(AiWebSearch.ONCE)) { _, _ -> }
            // Kept with the page it rests on alone.
            val text = checkNotNull(cache.cards[key])
            assertTrue(page.url in text)
            assertFalse(other.url in text)
            val kept = checkNotNull(checks.kept(key))
            assertEquals(VocalsState.Checked(VocalsAnswer.INSTRUMENTAL, key.model, page), kept.state)
            // The same key on the next play: the track, its length and album, not the evidence.
            assertEquals(key, checks.key(track.copy(evidence = EvidenceSet.of(emptyList(), JvmHasher)), settings))
            // A search that fails keeps nothing.
            outcome = WebSearchOutcome.Failed
            val live =
                checkNotNull(checks.key(track.copy(nowPlaying = track.nowPlaying.copy(album = "Live")), settings))
            val failed = checks.ask(live, track, AiSearches.Asking(AiWebSearch.ONCE)) { _, _ -> }
            assertEquals(VocalsState.Failed(AiSearches.WEB_SEARCH_FAILED), failed)
            assertNull(checks.kept(live))
        }

    @Test
    fun `a gate closed before the paid steps asks nothing and keeps nothing`() =
        runTest {
            val provider = FakeProvider { answer("vocals") }
            val checks = checks(provider)
            val key = checkNotNull(checks.key(track, settings))
            val end = checks.ask(key, track, AiSearches.Asking(AiWebSearch.ONCE), mayPay = { false }) { _, _ -> }
            assertEquals(VocalsState.Failed(VocalsChecks.REFUSED), end)
            assertTrue(queries.isEmpty() && provider.requests.isEmpty())
            assertNull(checks.kept(key))
        }
}
