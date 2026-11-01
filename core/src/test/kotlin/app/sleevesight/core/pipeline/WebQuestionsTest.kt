package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *AI web search*'s questions (#801): from knowledge one call; *Once* one search and one call with its
 * pages, none when the search fails or finds nothing; *Up to {n} times* a loop with the search tool,
 * at most n searches and n + 1 calls, the last with the tool not to be called; a service with no
 * tools asked as *Once*; every search and call counted as made; a stop cancels the loop.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class WebQuestionsTest {
    private val page = WebPage("https://www.discogs.com/release/1", "Grace", "Released 23 August 1994.")
    private val other = WebPage("https://example.org/grace", null, "Grace, 1994.")

    private val question =
        object : WebQuestion {
            override val knowledge = "KNOWLEDGE"
            override val query = "\"Grace\" by Jeff Buckley, release date"

            override fun once(pages: List<WebPage>) = "ONCE " + pages.joinToString { it.url }

            override fun agent(limit: Int) = "AGENT $limit"
        }

    private val base = CompletionRequest("m", "", 100)

    private class Searches(
        private val answer: (String) -> WebSearchOutcome,
    ) : WebSearcher {
        val queries = mutableListOf<String>()

        override suspend fun search(query: String): WebSearchOutcome {
            queries += query
            return answer(query)
        }
    }

    private fun toolTurn(vararg queries: String): List<CompletionEvent> =
        listOf(
            CompletionEvent.ToolUse(
                ToolTurn(
                    queries.mapIndexed { i, q -> ToolCall("call$i", WebPrompt.TOOL_NAME, """{"query":"$q"}""") },
                    "[]",
                ),
            ),
            CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
        )

    @Test
    fun offIsOneCallFromKnowledgeWithNoSearch() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("{}") }
            val web = Searches { WebSearchOutcome.Found(listOf(page)) }
            val counted = mutableListOf<QuestionCost>()
            val reply =
                WebQuestions(AiAccess(provider, Secret("k")), web).ask(question, AiWebSearch.OFF, 3, base) {
                    counted +=
                        it
                }
            assertEquals(listOf("KNOWLEDGE"), provider.requests.map { it.prompt })
            assertTrue(
                provider.requests
                    .single()
                    .tools
                    .isEmpty(),
            )
            assertTrue(web.queries.isEmpty())
            assertNull(reply.pages)
            assertEquals(QuestionCost(searches = 0, calls = 1), reply.cost)
            assertEquals(listOf(QuestionCost.CALL), counted)
        }

    @Test
    fun noEngineAsksFromKnowledgeWhateverTheSetting() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("{}") }
            val reply = WebQuestions(AiAccess(provider, Secret("k")), null).ask(question, AiWebSearch.UP_TO, 3, base)
            assertEquals(AiWebSearch.OFF, reply.used)
            assertEquals(listOf("KNOWLEDGE"), provider.requests.map { it.prompt })
        }

    @Test
    fun onceSearchesTheQueryAndGivesItsPagesInOneCall() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("{}") }
            val web = Searches { WebSearchOutcome.Found(listOf(page, other, page)) }
            val counted = mutableListOf<QuestionCost>()
            val reply =
                WebQuestions(AiAccess(provider, Secret("k")), web).ask(question, AiWebSearch.ONCE, 3, base) {
                    counted +=
                        it
                }
            assertEquals(listOf(question.query), web.queries)
            // Each page once.
            assertEquals(listOf("ONCE ${page.url}, ${other.url}"), provider.requests.map { it.prompt })
            assertTrue(
                provider.requests
                    .single()
                    .tools
                    .isEmpty(),
            )
            assertEquals(listOf(page, other), reply.pages)
            assertEquals(QuestionCost(searches = 1, calls = 1), reply.cost)
            assertEquals(listOf(QuestionCost.SEARCH, QuestionCost.CALL), counted)
        }

    @Test
    fun onceWithASearchThatFailsOrFindsNothingMakesNoCall() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("{}") }
            val failed =
                WebQuestions(AiAccess(provider, Secret("k")), Searches { WebSearchOutcome.Failed })
                    .ask(question, AiWebSearch.ONCE, 3, base)
            assertTrue(failed.searchFailed)
            val none =
                WebQuestions(AiAccess(provider, Secret("k")), Searches { WebSearchOutcome.Found(emptyList()) })
                    .ask(question, AiWebSearch.ONCE, 3, base)
            assertFalse(none.searchFailed)
            assertEquals(emptyList(), none.pages)
            assertNull(none.end)
            assertTrue(provider.requests.isEmpty())
            assertEquals(QuestionCost(searches = 1, calls = 0), none.cost)
        }

    @Test
    fun upToNLetsTheModelSearchThenMakesItAnswer() =
        runTest {
            // The model would search for ever: it is held to two searches, then asked to answer.
            val provider =
                FakeProvider(usesTools = true) { request ->
                    if (request.toolsAllowed) {
                        toolTurn(
                            "grace release ${request.exchanges.size}",
                        )
                    } else {
                        FakeProvider.done("{}")
                    }
                }
            val web = Searches { WebSearchOutcome.Found(listOf(page)) }
            val counted = mutableListOf<QuestionCost>()
            val reply =
                WebQuestions(AiAccess(provider, Secret("k")), web).ask(question, AiWebSearch.UP_TO, 2, base) {
                    counted +=
                        it
                }
            assertEquals(listOf("grace release 0", "grace release 1"), web.queries)
            assertEquals(3, provider.requests.size)
            assertEquals(listOf(true, true, false), provider.requests.map { it.toolsAllowed })
            assertTrue(provider.requests.all { it.prompt == "AGENT 2" && it.tools == listOf(WebPrompt.TOOL) })
            // Each call sends the exchanges so far, the search's pages as the tool's answer.
            assertEquals(listOf(0, 1, 2), provider.requests.map { it.exchanges.size })
            val result =
                provider.requests
                    .last()
                    .exchanges
                    .first()
                    .results
                    .single()
            assertEquals("call0", result.callId)
            assertEquals(WebPrompt.pages(listOf(page)), result.content)
            assertEquals(listOf(page), reply.pages)
            assertEquals(QuestionCost(searches = 2, calls = 3), reply.cost)
            assertEquals(5, counted.size)
            assertEquals(StopReason.END, (reply.end as CompletionEvent.Done).stopReason)
        }

    @Test
    fun callsBeyondTheLimitInOneTurnAreToldNoSearchesAreLeft() =
        runTest {
            val provider =
                FakeProvider(usesTools = true) { request ->
                    if (request.exchanges.isEmpty()) toolTurn("a", "b", "c") else FakeProvider.done("{}")
                }
            val web = Searches { WebSearchOutcome.Failed }
            val reply = WebQuestions(AiAccess(provider, Secret("k")), web).ask(question, AiWebSearch.UP_TO, 2, base)
            assertEquals(listOf("a", "b"), web.queries)
            val results =
                provider.requests[1]
                    .exchanges
                    .single()
                    .results
                    .map { it.content }
            assertEquals(listOf(WebPrompt.SEARCH_FAILED, WebPrompt.SEARCH_FAILED, WebPrompt.NO_SEARCHES_LEFT), results)
            // The limit reached: the next call may not search.
            assertFalse(provider.requests[1].toolsAllowed)
            assertEquals(QuestionCost(searches = 2, calls = 2), reply.cost)
        }

    @Test
    fun callsThatSearchNothingCannotRunPastTheCallBudget() =
        runTest {
            // A model that only ever calls the tool with no query: no search is made, yet the calls end.
            val bad =
                listOf(
                    CompletionEvent.ToolUse(ToolTurn(listOf(ToolCall("c", WebPrompt.TOOL_NAME, "{}")), "[]")),
                    CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
                )
            val provider = FakeProvider(usesTools = true) { bad }
            val web = Searches { WebSearchOutcome.Found(listOf(page)) }
            val reply = WebQuestions(AiAccess(provider, Secret("k")), web).ask(question, AiWebSearch.UP_TO, 2, base)
            assertTrue(web.queries.isEmpty())
            assertEquals(3, provider.requests.size)
            assertEquals(listOf(true, true, false), provider.requests.map { it.toolsAllowed })
            assertEquals(QuestionCost(searches = 0, calls = 3), reply.cost)
        }

    @Test
    fun musicBrainzPagesReachNoPromptNorToolResult() =
        runTest {
            // D12: an engine that lets MusicBrainz's pages through, its tags in their text.
            val tags = WebPage("https://beta.musicbrainz.org/recording/x", "Grace", "Tags: shoegaze")
            val web = Searches { WebSearchOutcome.Found(listOf(tags, page)) }
            val once = FakeProvider { FakeProvider.done("{}") }
            val reply = WebQuestions(AiAccess(once, Secret("k")), web).ask(question, AiWebSearch.ONCE, 3, base)
            assertEquals(listOf("ONCE ${page.url}"), once.requests.map { it.prompt })
            assertEquals(listOf(page), reply.pages)
            val loop =
                FakeProvider(usesTools = true) { request ->
                    if (request.exchanges.isEmpty()) toolTurn("grace") else FakeProvider.done("{}")
                }
            val looped = WebQuestions(AiAccess(loop, Secret("k")), web).ask(question, AiWebSearch.UP_TO, 3, base)
            val result =
                loop.requests[1]
                    .exchanges
                    .single()
                    .results
                    .single()
                    .content
            assertEquals(WebPrompt.pages(listOf(page)), result)
            assertFalse("shoegaze" in result)
            assertEquals(listOf(page), looped.pages)
            // Only MusicBrainz's found: nothing to answer from.
            val only = Searches { WebSearchOutcome.Found(listOf(tags)) }
            val none = WebQuestions(AiAccess(FakeProvider { FakeProvider.done("{}") }, Secret("k")), only)
            assertEquals(emptyList(), none.ask(question, AiWebSearch.ONCE, 3, base).pages)
        }

    @Test
    fun aServiceWithNoToolsIsAskedAsOnce() =
        runTest {
            val provider = FakeProvider(usesTools = false) { FakeProvider.done("{}") }
            val web = Searches { WebSearchOutcome.Found(listOf(page)) }
            val reply = WebQuestions(AiAccess(provider, Secret("k")), web).ask(question, AiWebSearch.UP_TO, 3, base)
            assertEquals(AiWebSearch.ONCE, reply.used)
            assertEquals(listOf("ONCE ${page.url}"), provider.requests.map { it.prompt })
        }

    @Test
    fun aStopCancelsTheLoopBeforeItsNextSearch() =
        runTest {
            val held = CompletableDeferred<Unit>()
            val provider = FakeProvider(usesTools = true) { toolTurn("q") }
            val web =
                object : WebSearcher {
                    var searched = 0

                    override suspend fun search(query: String): WebSearchOutcome {
                        searched++
                        held.await()
                        return WebSearchOutcome.Found(listOf(page))
                    }
                }
            val job =
                launch {
                    WebQuestions(AiAccess(provider, Secret("k")), web).ask(question, AiWebSearch.UP_TO, 3, base)
                }
            runCurrent()
            job.cancel()
            held.complete(Unit)
            runCurrent()
            assertEquals(1, web.searched)
            assertEquals(1, provider.requests.size)
        }
}
