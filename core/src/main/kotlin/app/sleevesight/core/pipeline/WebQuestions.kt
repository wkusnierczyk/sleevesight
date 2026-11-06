package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolExchange
import app.sleevesight.core.ai.ToolResult
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.sources.isMusicBrainzPage
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
    /**
     * The system prompt of [knowledge]'s and [once]'s calls (#767), or null to keep the request's
     * own (`CompletionRequest.system`).
     */
    val system: String? get() = null

    /** The prompt with *AI web search* off: the model answers from what it knows. */
    val knowledge: String

    /** *Once*'s search, in words. */
    val query: String

    /**
     * The names the question is about, which every search's pages are cleaned of page boilerplate
     * for ([WebSearcher.search]); none, the default, cleans only what is boilerplate whatever the
     * subject.
     */
    val subjectNames: List<String> get() = emptyList()

    /** *Once*'s prompt, with the [pages] its search found. */
    fun once(pages: List<WebPage>): String

    /** *Up to 3 times*' prompt: the model searches up to [limit] times with [WebPrompt.TOOL]. */
    fun agent(limit: Int): String

    /**
     * The system prompt of *Up to 3 times*' calls, every turn of the loop (#767): [system] unless
     * the question says how to search in it; null keeps the request's own.
     */
    fun agentSystem(limit: Int): String? = system

    /**
     * The search tool's answer with a search's [pages], none of them MusicBrainz's: [WebPrompt.pages],
     * unless the question numbers them as evidence lines of its own (a genre's reason, #762).
     */
    fun toolResult(pages: List<WebPage>): String = WebPrompt.pages(pages)
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
 * *Up to 3 times* with a service that takes no tools). [searchFailed]: *Once*'s search failed,
 * so no call was made. [refused]: the caller's gate ([WebQuestions.ask]'s `mayPay`) closed before a
 * search or a call, which was not made, nor any after it; what the reply holds is incomplete.
 */
@Suppress("LongParameterList") // What came back, what it cost, how it was asked, and how it ended.
class WebReply(
    val text: String,
    val end: CompletionEvent?,
    val pages: List<WebPage>?,
    val cost: QuestionCost,
    val used: AiWebSearch,
    val searchFailed: Boolean = false,
    val refused: Boolean = false,
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
 *   [limit] + 1 calls, the calls bounded apart from the searches, so turns whose calls search
 *   nothing (no query, another tool) cannot go on past them. A call beyond the limit gets
 *   [WebPrompt.NO_SEARCHES_LEFT]; a search that fails, [WebPrompt.SEARCH_FAILED], marked an error,
 *   as is a call with no query; and when searches were made and every one failed, the question has
 *   failed ([WebReply.searchFailed]), so its answer is not kept. A service whose
 *   client takes no tools (`AiProvider.usesTools`, Google's) is asked as with *Once*.
 *
 * A search's pages on MusicBrainz are left out of the prompt and the tool's results (D12: its pages
 * carry its tags), whatever the engine let through ([isMusicBrainzPage]).
 *
 * Each search and call is counted to [ask]'s `counted` as it is made, before its answer, so one
 * stopped or failed still counts. Cancelling the caller cancels the whole loop: the call under way
 * and any search after it. [ask]'s `mayPay` is asked right before each search and each call, with
 * no suspension between: once it says no, that one and every one after it are not made
 * ([WebReply.refused]), so a gate that closes while a call streams (the app left, web search
 * switched off, #912) stops the loop at its next paid step.
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
     * [base] is but for its prompt and tools; each search and call counted to [counted] as made, and
     * made only while [mayPay], asked right before it, says so.
     */
    @Suppress("LongParameterList") // The question, how it searches, its request, and its cost and gate.
    suspend fun ask(
        question: WebQuestion,
        search: AiWebSearch,
        limit: Int,
        base: CompletionRequest,
        counted: (QuestionCost) -> Unit = {},
        mayPay: () -> Boolean = { true },
    ): WebReply {
        val way = used(search)
        val paying = Paying(counted, mayPay)
        return when (way) {
            AiWebSearch.OFF -> {
                val request = base.copy(prompt = question.knowledge, system = question.system ?: base.system)
                if (!mayPay()) return WebReply("", null, null, QuestionCost(), way, refused = true)
                val (text, end) = call(request, counted)
                WebReply(text, end, pages = null, QuestionCost.CALL, way)
            }
            AiWebSearch.ONCE -> once(question, base, paying)
            AiWebSearch.UP_TO -> agent(question, limit, base, paying)
        }
    }

    // How a question pays: each search and call counted to [counted], made only while [mayPay].
    private class Paying(
        val counted: (QuestionCost) -> Unit,
        val mayPay: () -> Boolean,
    )

    private suspend fun once(
        question: WebQuestion,
        base: CompletionRequest,
        paying: Paying,
    ): WebReply =
        if (paying.mayPay()) {
            searchedOnce(question, base, paying)
        } else {
            WebReply("", null, null, QuestionCost(), AiWebSearch.ONCE, refused = true)
        }

    // *Once*'s search, its gate open, then its call, while the gate is still open.
    private suspend fun searchedOnce(
        question: WebQuestion,
        base: CompletionRequest,
        paying: Paying,
    ): WebReply {
        paying.counted(QuestionCost.SEARCH)
        val found = checkNotNull(web).search(question.query, question.subjectNames)
        val pages = (found as? WebSearchOutcome.Found)?.pages.orEmpty().let(::usable)
        return when {
            found !is WebSearchOutcome.Found || pages.isEmpty() ->
                WebReply(
                    "",
                    null,
                    pages,
                    QuestionCost.SEARCH,
                    AiWebSearch.ONCE,
                    searchFailed = found !is WebSearchOutcome.Found,
                )
            !paying.mayPay() -> WebReply("", null, pages, QuestionCost.SEARCH, AiWebSearch.ONCE, refused = true)
            else -> {
                val request = base.copy(prompt = question.once(pages), system = question.system ?: base.system)
                val (text, end) = call(request, paying.counted)
                WebReply(text, end, pages, QuestionCost(searches = 1, calls = 1), AiWebSearch.ONCE)
            }
        }
    }

    private suspend fun agent(
        question: WebQuestion,
        limit: Int,
        base: CompletionRequest,
        paying: Paying,
    ): WebReply {
        val loop = AgentLoop(question, limit, base, paying, checkNotNull(web))
        var reply: WebReply? = null
        while (reply == null) reply = loop.turn()
        return reply
    }

    // *Up to 3 times*' loop (see the class): what it has found and paid for so far, a turn at a time.
    private inner class AgentLoop(
        private val question: WebQuestion,
        private val limit: Int,
        private val base: CompletionRequest,
        private val paying: Paying,
        private val engine: WebSearcher,
    ) {
        private val prompt = question.agent(limit)
        private val system = question.agentSystem(limit) ?: base.system
        private val pages = LinkedHashMap<String, WebPage>()
        private val exchanges = mutableListOf<ToolExchange>()
        private var cost = QuestionCost()
        private var failed = 0

        // One call, and the searches it asks for: the question's reply when it ends here, else null.
        suspend fun turn(): WebReply? {
            // Searches left, and calls: at most [limit] + 1 in all, the last never with the tool,
            // whatever the turns' calls look like (one with no query counts no search).
            val allowed = cost.searches < limit && cost.calls < limit
            val request =
                base.copy(
                    prompt = prompt,
                    system = system,
                    tools = listOf(WebPrompt.TOOL),
                    exchanges = exchanges.toList(),
                    toolsAllowed = allowed,
                )
            if (!paying.mayPay()) return refused()
            cost += QuestionCost.CALL
            val (text, end, turn) = turn(request, paying.counted)
            val calledTools = (end as? CompletionEvent.Done)?.stopReason == StopReason.TOOL_USE && turn != null
            // Searches made and every one failed: the answer had nothing to go on, and is not kept.
            val allFailed = cost.searches > 0 && failed == cost.searches
            return if (!calledTools || !allowed) {
                WebReply(text, end, pages.values.toList(), cost, AiWebSearch.UP_TO, searchFailed = allFailed)
            } else {
                searched(checkNotNull(turn))
            }
        }

        // The searches [turn] asks for, answered for the next call; the question refused, when the gate
        // closed before one.
        private suspend fun searched(turn: ToolTurn): WebReply? {
            val results = mutableListOf<ToolResult>()
            for (call in turn.calls) {
                val answer = answer(question, call, engine, cost.searches < limit, pages, paying)
                if (answer.refused) return refused()
                if (answer.searched) cost += QuestionCost.SEARCH
                if (answer.failed) failed++
                results += answer.result
            }
            exchanges += ToolExchange(turn, results)
            return null
        }

        private fun refused() = WebReply("", null, pages.values.toList(), cost, AiWebSearch.UP_TO, refused = true)
    }

    // What the search tool answers [call] (searching with [engine] when [searchesLeft], counted, its
    // pages added to [pages]): the pages; that the search found none, or failed (an error); that no
    // searches are left; or, for a call with no query or to another tool, an error. Refused, with no
    // search, once [paying]'s gate has closed.
    @Suppress("LongParameterList") // The question, the call, the engine, and what the loop keeps.
    private suspend fun answer(
        question: WebQuestion,
        call: ToolCall,
        engine: WebSearcher,
        searchesLeft: Boolean,
        pages: MutableMap<String, WebPage>,
        paying: Paying,
    ): ToolAnswer {
        val query = queryOf(call.arguments)
        return when {
            call.name != WebPrompt.TOOL_NAME || query == null -> {
                ToolAnswer(ToolResult(call.id, WebPrompt.SEARCH_FAILED, isError = true))
            }
            !searchesLeft -> {
                ToolAnswer(ToolResult(call.id, WebPrompt.NO_SEARCHES_LEFT))
            }
            !paying.mayPay() -> {
                ToolAnswer(ToolResult(call.id, WebPrompt.SEARCH_FAILED, isError = true), refused = true)
            }
            else -> {
                paying.counted(QuestionCost.SEARCH)
                val found = engine.search(query, question.subjectNames)
                val kept = (found as? WebSearchOutcome.Found)?.pages?.let(::usable).orEmpty()
                kept.forEach { pages.putIfAbsent(it.url, it) }
                val result =
                    when {
                        found !is WebSearchOutcome.Found -> ToolResult(call.id, WebPrompt.SEARCH_FAILED, isError = true)
                        kept.isEmpty() -> ToolResult(call.id, WebPrompt.FOUND_NOTHING)
                        else -> ToolResult(call.id, question.toolResult(kept))
                    }
                ToolAnswer(result, searched = true, failed = found !is WebSearchOutcome.Found)
            }
        }
    }

    // A tool's answer, whether a search was made for it, whether that search failed, and whether it
    // was refused, the gate closed.
    private class ToolAnswer(
        val result: ToolResult,
        val searched: Boolean = false,
        val failed: Boolean = false,
        val refused: Boolean = false,
    )

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
        // Of [found], each page once, none of MusicBrainz's, whose tags never reach a prompt (D12),
        // whatever the engine let through.
        fun usable(found: List<WebPage>): List<WebPage> =
            found.distinctBy { it.url }.filterNot { isMusicBrainzPage(it.url) }

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
