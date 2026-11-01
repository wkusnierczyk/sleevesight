package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolExchange
import app.sleevesight.core.ai.ToolResult
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.serialization.json.JsonPrimitive

/**
 * One question to the AI service, in the prompts of each way *AI web search* asks it (#801): from the
 * model's knowledge ([knowledge]), with the pages of the app's one search for [query] ([once]), or
 * with the model's own searches, up to a limit ([agent]). What *Ask AI* asks (`AiSearches`), and what
 * another question that searches the web may ask (#762's genre reasons).
 */
interface WebQuestion {
    /** The prompt with *AI web search* off: the model answers from what it knows. */
    val knowledge: String

    /** *Once*'s search, in words. */
    val query: String

    /** *Once*'s prompt, with the [pages] its search found. */
    fun once(pages: List<WebPage>): String

    /** *Up to {n} times*' prompt: the model searches up to [limit] times with [WebPrompt.TOOL]. */
    fun agent(limit: Int): String
}

/** What a question cost (#801): the web searches made and the AI service's calls, each paid for. */
data class QuestionCost(
    val searches: Int = 0,
    val calls: Int = 0,
) {
    operator fun plus(other: QuestionCost): QuestionCost = QuestionCost(searches + other.searches, calls + other.calls)

    companion object {
        /** One web search. */
        val SEARCH: QuestionCost = QuestionCost(searches = 1)

        /** One AI call. */
        val CALL: QuestionCost = QuestionCost(calls = 1)
    }
}

/**
 * What a question came back with ([WebQuestions.ask]): the last call's [text] and its [end] (null
 * when no call was made); every page the searches found, once each ([pages]), null for a question
 * from the model's knowledge; what it cost ([cost]); and the way it was asked ([used]: *Once* for
 * *Up to {n} times* with a service that takes no tools). [searchFailed]: *Once*'s search failed,
 * so no call was made.
 */
class WebReply(
    val text: String,
    val end: CompletionEvent?,
    val pages: List<WebPage>?,
    val cost: QuestionCost,
    val used: AiWebSearch,
    val searchFailed: Boolean = false,
)

/**
 * Asks [WebQuestion]s of the AI service ([ai]) with *AI web search* (#801; `design/design.md`, *Ask
 * AI*; `design/architecture.md`, *Synthesis and grounding*), searching with [web]:
 *
 * - [AiWebSearch.OFF], or no [web]: one call, the question from the model's knowledge.
 * - [AiWebSearch.ONCE]: one search for the question's query, then one call with its pages. A search
 *   that fails makes no call ([WebReply.searchFailed]); one that finds no page makes none either, as
 *   there is nothing to answer from.
 * - [AiWebSearch.UP_TO]: a loop of calls with the search tool ([WebPrompt.TOOL]). Each turn that
 *   calls it is answered with each search's pages ([WebPrompt.pages]), the searches counted; once
 *   [limit] searches are made, a call more with the tool declared but not to be called
 *   (`CompletionRequest.toolsAllowed` false), so the model answers: at most [limit] searches and
 *   [limit] + 1 calls. A call beyond the limit gets [WebPrompt.NO_SEARCHES_LEFT]; a search that
 *   fails, [WebPrompt.SEARCH_FAILED]. A service whose client takes no tools
 *   (`AiProvider.usesTools`, Google's) is asked as with *Once*.
 *
 * Each search and call is counted to [ask]'s `counted` as it is made, before its answer, so one
 * stopped or failed still counts. Cancelling the caller cancels the whole loop: the call under way
 * and any search after it.
 */
class WebQuestions(
    private val ai: AiAccess,
    private val web: WebSearcher?,
) {
    /**
     * The way [search] asks with this service and search: [AiWebSearch.OFF] with no search, *Once*
     * for a loop the service cannot run.
     */
    fun used(search: AiWebSearch): AiWebSearch =
        when {
            web == null -> AiWebSearch.OFF
            search == AiWebSearch.UP_TO && !ai.provider.usesTools -> AiWebSearch.ONCE
            else -> search
        }

    /**
     * Asks [question] with [search] (up to [limit] searches for [AiWebSearch.UP_TO]), each call as
     * [base] is but for its prompt and tools; each search and call counted to [counted] as made.
     */
    suspend fun ask(
        question: WebQuestion,
        search: AiWebSearch,
        limit: Int,
        base: CompletionRequest,
        counted: (QuestionCost) -> Unit = {},
    ): WebReply =
        when (val way = used(search)) {
            AiWebSearch.OFF -> {
                val (text, end) = call(base.copy(prompt = question.knowledge), counted)
                WebReply(text, end, pages = null, QuestionCost.CALL, way)
            }
            AiWebSearch.ONCE -> once(question, base, counted)
            AiWebSearch.UP_TO -> agent(question, limit, base, counted)
        }

    private suspend fun once(
        question: WebQuestion,
        base: CompletionRequest,
        counted: (QuestionCost) -> Unit,
    ): WebReply {
        counted(QuestionCost.SEARCH)
        val found = checkNotNull(web).search(question.query)
        val pages = (found as? WebSearchOutcome.Found)?.pages.orEmpty().distinctBy { it.url }
        if (found !is WebSearchOutcome.Found || pages.isEmpty()) {
            return WebReply(
                "",
                null,
                pages,
                QuestionCost.SEARCH,
                AiWebSearch.ONCE,
                searchFailed = found !is WebSearchOutcome.Found,
            )
        }
        val (text, end) = call(base.copy(prompt = question.once(pages)), counted)
        return WebReply(text, end, pages, QuestionCost(searches = 1, calls = 1), AiWebSearch.ONCE)
    }

    private suspend fun agent(
        question: WebQuestion,
        limit: Int,
        base: CompletionRequest,
        counted: (QuestionCost) -> Unit,
    ): WebReply {
        val engine = checkNotNull(web)
        val prompt = question.agent(limit)
        val pages = LinkedHashMap<String, WebPage>()
        val exchanges = mutableListOf<ToolExchange>()
        var cost = QuestionCost()
        while (true) {
            val allowed = cost.searches < limit
            val request =
                base.copy(
                    prompt = prompt,
                    tools = listOf(WebPrompt.TOOL),
                    exchanges = exchanges.toList(),
                    toolsAllowed = allowed,
                )
            cost += QuestionCost.CALL
            val (text, end, turn) = turn(request, counted)
            val calledTools = (end as? CompletionEvent.Done)?.stopReason == StopReason.TOOL_USE && turn != null
            if (!calledTools || !allowed) return WebReply(text, end, pages.values.toList(), cost, AiWebSearch.UP_TO)
            val results =
                checkNotNull(turn).calls.map { call ->
                    val query = queryOf(call.arguments)
                    val content =
                        when {
                            call.name != WebPrompt.TOOL_NAME || query == null -> WebPrompt.SEARCH_FAILED
                            cost.searches >= limit -> WebPrompt.NO_SEARCHES_LEFT
                            else -> {
                                cost += QuestionCost.SEARCH
                                counted(QuestionCost.SEARCH)
                                when (val found = engine.search(query)) {
                                    WebSearchOutcome.Failed -> WebPrompt.SEARCH_FAILED
                                    is WebSearchOutcome.Found ->
                                        if (found.pages.isEmpty()) {
                                            WebPrompt.FOUND_NOTHING
                                        } else {
                                            found.pages.forEach { pages.putIfAbsent(it.url, it) }
                                            WebPrompt.pages(found.pages)
                                        }
                                }
                            }
                        }
                    ToolResult(call.id, content)
                }
            exchanges += ToolExchange(turn, results)
        }
    }

    // One call of [request], counted: its text and its end.
    private suspend fun call(
        request: CompletionRequest,
        counted: (QuestionCost) -> Unit,
    ): Pair<String, CompletionEvent?> {
        val (text, end) = turn(request, counted)
        return text to end
    }

    // One call of [request], counted: its text, its end and the tool calls it ended with, if any.
    private suspend fun turn(
        request: CompletionRequest,
        counted: (QuestionCost) -> Unit,
    ): Turn {
        counted(QuestionCost.CALL)
        val text = StringBuilder()
        var end: CompletionEvent? = null
        var tools: ToolTurn? = null
        ai.provider.stream(request, ai.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> text.append(event.text)
                is CompletionEvent.ToolUse -> tools = event.turn
                is CompletionEvent.Done, is CompletionEvent.Error -> end = event
            }
        }
        return Turn(text.toString(), end, tools)
    }

    private data class Turn(
        val text: String,
        val end: CompletionEvent?,
        val tools: ToolTurn?,
    )

    private companion object {
        // The query in a call's [arguments], the tool's JSON input; null when it has none.
        fun queryOf(arguments: String): String? =
            AiSearchAnswers
                .objectIn(arguments)
                ?.get(WebPrompt.QUERY)
                ?.let { it as? JsonPrimitive }
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }
}
