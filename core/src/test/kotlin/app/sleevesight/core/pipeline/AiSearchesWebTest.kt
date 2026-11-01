package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.synthesis.AiSearchPrompt
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * *Ask AI* with *AI web search* (#801): an answer from the web has its own key, kept with its pages
 * and how it was asked, and read back checked against those pages; with Exa web search off, or for a
 * genre's definition, the question is from the model's knowledge; a failed search fails the question
 * with no call and keeps nothing.
 */
class AiSearchesWebTest {
    private val page = WebPage("https://www.discogs.com/release/1", "Grace", "Grace was released on 23 August 1994.")
    private var outcome: WebSearchOutcome = WebSearchOutcome.Found(listOf(page))
    private val provider =
        FakeProvider {
            FakeProvider.done("""{"album_date": {"date": "1994-08-23", "source": "${page.url}"}}""")
        }
    private val cache = MemoryCache()
    private val answers =
        app.sleevesight.core.synthesis
            .AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)
    private val searches =
        AiSearches(AiAccess(provider, Secret("k")), cache, JvmHasher, answers, web = { outcome })
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val playing = nowPlaying("Grace", "Jeff Buckley", "Grace")
    private val track = TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), sources = emptyMap())
    private val facts = SearchFacts("Grace", listOf("Jeff Buckley"), "Grace")

    @Test
    fun theWebHasItsOwnKeyAndExaOffOrADefinitionAsksFromKnowledge() {
        val web = checkNotNull(searches.key(track, SearchKind.DATES, settings))
        assertFalse(web.modelKnowledge)
        assertEquals(AiSearchPrompt.WEB_VERSION, web.promptVersion)
        val exaOff = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WEB)
        val knowledge = checkNotNull(searches.key(track, SearchKind.DATES, exaOff))
        assertTrue(knowledge.modelKnowledge)
        assertNotEquals(web, knowledge)
        assertEquals(AiWebSearch.OFF, searches.used(SearchKind.DATES, exaOff))
        assertEquals(AiWebSearch.OFF, searches.used(SearchKind.DEFINITION, settings))
        assertEquals(AiWebSearch.OFF, searches.used(SearchKind.DATES, settings.copy(aiWebSearch = AiWebSearch.OFF)))
    }

    @Test
    fun anAnswerFromTheWebIsShownWithItsPageAndKeptWithHowItWasAsked() =
        runTest {
            val key = checkNotNull(searches.key(track, SearchKind.DATES, settings))
            val shown = mutableListOf<SearchState>()
            val asking = AiSearches.Asking(AiWebSearch.ONCE, 3, automatic = true)
            val last = searches.search(key, SearchKind.DATES, track, facts, { s, _ -> shown += s }, asking = asking)
            assertEquals(SearchState.Searching(web = true), shown.first())
            assertIs<SearchState.Answered>(last)
            assertEquals(SearchAnswer.Dates(album = "1994-08-23", albumSource = page.url), last.answer)
            assertEquals(SearchWay(automatic = true, web = true, QuestionCost(searches = 1, calls = 1)), last.way)
            // Read back from the cache as it was shown.
            assertEquals(last, searches.kept(key, SearchKind.DATES, track)?.state)
        }

    @Test
    fun aSearchThatFailsFailsTheQuestionWithNoCallAndKeepsNothing() =
        runTest {
            outcome = WebSearchOutcome.Failed
            val key = checkNotNull(searches.key(track, SearchKind.DATES, settings))
            val last =
                searches.search(
                    key,
                    SearchKind.DATES,
                    track,
                    facts,
                    { _, _ -> },
                    asking = AiSearches.Asking(AiWebSearch.ONCE),
                )
            assertEquals(SearchState.Failed(AiSearches.WEB_SEARCH_FAILED), last)
            assertTrue(provider.requests.isEmpty())
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun aSearchThatFindsNoPageIsNothingFoundOnTheWebAndKept() =
        runTest {
            outcome = WebSearchOutcome.Found(emptyList())
            val key = checkNotNull(searches.key(track, SearchKind.DATES, settings))
            val last =
                searches.search(
                    key,
                    SearchKind.DATES,
                    track,
                    facts,
                    { _, _ -> },
                    asking = AiSearches.Asking(AiWebSearch.ONCE),
                )
            assertIs<SearchState.NothingFound>(last)
            assertTrue(last.way.web)
            assertEquals(last, searches.kept(key, SearchKind.DATES, track)?.state)
        }

    @Test
    fun aReplyKeptBeforeTheEnvelopeIsReadAsAnsweredByATapFromKnowledge() =
        runTest {
            val exaOff = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WEB)
            val key = checkNotNull(searches.key(track, SearchKind.DATES, exaOff))
            cache.cards[key] = """{"album_date": "1994-08-23"}"""
            val kept = searches.kept(key, SearchKind.DATES, track)?.state
            assertEquals(SearchState.Answered(SearchAnswer.Dates(album = "1994-08-23")), kept)
        }
}
