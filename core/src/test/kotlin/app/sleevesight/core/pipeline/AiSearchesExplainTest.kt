package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.AiSearchPrompt
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * *Explain the song* (#908): offered only with *AI web search* on as used, with no key while it is
 * off; asked from the track's own pages about the song and the pages its searches find, numbered in
 * one run, and kept with them, so a kept explanation reads back as it was shown.
 */
class AiSearchesExplainTest {
    private val found =
        WebPage("https://music-mag.example/interview", "Interview: on Victorian America", "She wrote it about loss.")
    private var outcome: WebSearchOutcome = WebSearchOutcome.Found(listOf(found))
    private val provider =
        FakeProvider { FakeProvider.done("""{"explanation": "It is about loss. [S2] The oak lasts. [S1]"}""") }
    private val cache = MemoryCache()
    private val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)
    private val searches =
        AiSearches(AiAccess(provider, Secret("k")), cache, JvmHasher, answers, web = { _, _ -> outcome })
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val at = Instant.parse("2026-11-01T10:00:00Z")
    private val wikipedia = "https://en.wikipedia.org/wiki/The_Black_Oak"
    private val evidence =
        EvidenceSet.of(
            listOf(
                EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "The song is about an oak.", wikipedia, at),
                EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "It was released in 2009.", wikipedia, at),
                EvidenceDraft(SourceId.WIKIPEDIA, Scope.ARTIST, "She is a singer.", "https://en.wikipedia.org/a", at),
                EvidenceDraft(SourceId.MUSICBRAINZ, Scope.SONG, "Recorded in 2008.", "https://musicbrainz.org/r", at),
                EvidenceDraft(SourceId.LASTFM, Scope.SONG, "Many listeners.", null, at),
                EvidenceDraft(
                    SourceId.LASTFM,
                    Scope.SONG,
                    "1,204 listeners tag it folk.",
                    "https://www.last.fm/music/x",
                    at,
                    aggregate = true,
                ),
            ),
            JvmHasher,
        )
    private val playing = nowPlaying("The Black Oak", "Emily Jane White", "Victorian America")
    private val track =
        TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), sources = emptyMap(), evidence = evidence)
    private val facts = SearchFacts("The Black Oak", listOf("Emily Jane White"), "Victorian America")

    @Test
    fun offeredAndKeyedOnlyWithAiWebSearchOnAsUsed() {
        assertTrue(searches.offered(SearchKind.EXPLAIN, settings))
        val key = checkNotNull(searches.key(track, SearchKind.EXPLAIN, settings))
        assertFalse(key.modelKnowledge)
        assertEquals(AiSearchPrompt.EXPLAIN_VERSION, key.promptVersion)
        val exaOff = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WEB)
        val webOff = settings.copy(aiWebSearch = AiWebSearch.OFF)
        for (off in listOf(exaOff, webOff)) {
            assertFalse(searches.offered(SearchKind.EXPLAIN, off))
            assertNull(searches.key(track, SearchKind.EXPLAIN, off))
            // The lyrics search is still offered, from the model's knowledge.
            assertTrue(searches.offered(SearchKind.LYRICS, off))
        }
        val noEngine = AiSearches(AiAccess(provider, Secret("k")), cache, JvmHasher, answers)
        assertFalse(noEngine.offered(SearchKind.EXPLAIN, settings))
        // No AI service: offered, disabled (no key), as the others are.
        val noAi = AiSearches(null, cache, JvmHasher, answers, web = { _, _ -> outcome })
        assertTrue(noAi.offered(SearchKind.EXPLAIN, settings))
        assertNull(noAi.key(track, SearchKind.EXPLAIN, settings))
    }

    @Test
    fun theTracksOwnPagesAboutTheSongComeFirstOnePerAddressNoneOfMusicBrainzsNorAggregates() {
        val pages = ExplainQuestion.pagesOf(evidence)
        assertEquals(1, pages.size)
        assertEquals(wikipedia, pages.single().url)
        assertEquals("Wikipedia", pages.single().title)
        assertEquals("The song is about an oak. It was released in 2009.", pages.single().text)
        assertTrue(ExplainQuestion.pagesOf(null).isEmpty())
    }

    @Test
    fun onceAsksWithEveryPageNumberedAndKeepsThemSoAKeptAnswerReadsBackTheSame() =
        runTest {
            val key = checkNotNull(searches.key(track, SearchKind.EXPLAIN, settings))
            val shown = mutableListOf<SearchState>()
            val last =
                searches.search(
                    key,
                    SearchKind.EXPLAIN,
                    track,
                    facts,
                    { s, _ -> shown += s },
                    asking = AiSearches.Asking(AiWebSearch.ONCE),
                )
            assertEquals(SearchState.Searching(web = true), shown.first())
            val prompt = provider.requests.single().prompt
            assertTrue("[S1] $wikipedia" in prompt)
            assertTrue("[S2] ${found.url}" in prompt)
            val answered = assertIs<SearchState.Answered>(last)
            val explanation = assertIs<SearchAnswer.Explanation>(answered.answer)
            assertEquals(
                listOf(CitedPage("S2", found.url, found.title), CitedPage("S1", wikipedia, "Wikipedia")),
                explanation.pages,
            )
            assertEquals(SearchWay(web = true, cost = QuestionCost(searches = 1, calls = 1)), answered.way)
            assertEquals(last, searches.kept(key, SearchKind.EXPLAIN, track)?.state)
            // Read against the pages kept with it, not the track's evidence now.
            val later = track.copy(evidence = null)
            assertEquals(last, searches.kept(key, SearchKind.EXPLAIN, later)?.state)
        }

    @Test
    fun upToNNumbersEachSearchsNewPagesOnAfterThoseGiven() =
        runTest {
            val loop =
                FakeProvider(usesTools = true) { request ->
                    if (request.exchanges.isEmpty()) {
                        listOf(
                            CompletionEvent.ToolUse(
                                ToolTurn(listOf(ToolCall("c", "exa_search", "{\"query\":\"The Black Oak\"}")), "[]"),
                            ),
                            CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
                        )
                    } else {
                        FakeProvider.done("""{"explanation": "It is about loss. [S2]"}""")
                    }
                }
            val made = AiSearches(AiAccess(loop, Secret("k")), cache, JvmHasher, answers, web = { _, _ -> outcome })
            val key = checkNotNull(made.key(track, SearchKind.EXPLAIN, settings))
            val last =
                made.search(
                    key,
                    SearchKind.EXPLAIN,
                    track,
                    facts,
                    { _, _ -> },
                    asking = AiSearches.Asking(AiWebSearch.UP_TO, 3),
                )
            val first = loop.requests.first()
            assertTrue("[S1] $wikipedia" in first.prompt)
            assertTrue("at most 3 times" in first.prompt)
            val result =
                loop.requests
                    .last()
                    .exchanges
                    .single()
                    .results
                    .single()
            assertTrue("[S2] ${found.url}" in result.content)
            val explanation = assertIs<SearchAnswer.Explanation>(assertIs<SearchState.Answered>(last).answer)
            assertEquals(listOf(CitedPage("S2", found.url, found.title)), explanation.pages)
        }

    @Test
    fun aPageFoundAgainKeepsItsIdWithThePassageItLackedJoinedOnce() =
        runTest {
            val meaning = WebPage(wikipedia, "The Black Oak – Wikipedia", "The oak stands for a love that outlasts.")
            outcome = WebSearchOutcome.Found(listOf(meaning, found))
            val key = checkNotNull(searches.key(track, SearchKind.EXPLAIN, settings))
            val last =
                searches.search(
                    key,
                    SearchKind.EXPLAIN,
                    track,
                    facts,
                    { _, _ -> },
                    asking = AiSearches.Asking(AiWebSearch.ONCE),
                )
            val prompt = provider.requests.single().prompt
            assertEquals(1, Regex("\\[S\\d+] ${Regex.escape(wikipedia)}").findAll(prompt).count())
            assertTrue(
                "Text: The song is about an oak. It was released in 2009. The oak stands for a love that outlasts." in
                    prompt,
            )
            // Its id and title stay; the next page is numbered on.
            assertTrue("[S2] ${found.url}" in prompt)
            val explanation = assertIs<SearchAnswer.Explanation>(assertIs<SearchState.Answered>(last).answer)
            assertEquals(CitedPage("S1", wikipedia, "Wikipedia"), explanation.pages.last())
            assertEquals(last, searches.kept(key, SearchKind.EXPLAIN, track.copy(evidence = null))?.state)
        }

    @Test
    fun upToNGivesAPageFoundAgainUnderItsIdOnlyWithAPassageItLacked() {
        val question = checkNotNull(ExplainQuestion.of(SearchKind.EXPLAIN, facts, evidence))
        val meaning = WebPage(wikipedia, null, "The oak stands for a love that outlasts.")
        val result = question.toolResult(listOf(meaning, found))
        assertTrue("[S1] $wikipedia" in result)
        assertTrue("Text: The song is about an oak. It was released in 2009. The oak stands for" in result)
        assertTrue("[S2] ${found.url}" in result)
        // The same passages again: nothing new.
        assertEquals(ExplainQuestion.ALREADY_GIVEN, question.toolResult(listOf(meaning, found)))
        assertEquals(listOf("S1", "S2"), question.sources.map { it.id })
        assertEquals("Wikipedia", question.sources.first().title)
    }

    @Test
    fun noPageFoundIsNothingFoundWithNoCall() =
        runTest {
            outcome = WebSearchOutcome.Found(emptyList())
            val key = checkNotNull(searches.key(track, SearchKind.EXPLAIN, settings))
            val last =
                searches.search(
                    key,
                    SearchKind.EXPLAIN,
                    track,
                    facts,
                    { _, _ -> },
                    asking = AiSearches.Asking(AiWebSearch.ONCE),
                )
            assertIs<SearchState.NothingFound>(last)
            assertTrue(provider.requests.isEmpty())
        }
}
