package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.AiSearchPrompt
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import app.sleevesight.core.synthesis.SearchReading
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** A question of *Ask AI* (#773; *Search with AI* until #802), as its panel shows it. */
sealed interface SearchState {
    /**
     * The question is under way: the turning record and "Searching the web with AI…", or, from the
     * model's knowledge ([web] false), "Asking AI…", with *Stop*.
     */
    data class Searching(
        val web: Boolean = false,
    ) : SearchState

    /** The answer, as checked, and how it was asked ([way]). */
    data class Answered(
        val answer: SearchAnswer,
        val way: SearchWay = SearchWay(),
    ) : SearchState

    /**
     * The answer had nothing usable: empty, or nothing in it passed the checks; or, from the web, the
     * search found no page. Kept, as an answer is.
     */
    data class NothingFound(
        val way: SearchWay = SearchWay(),
    ) : SearchState

    /** The call failed, for [reason] (a `ReplyFailures` label, or [AiSearches.MALFORMED]); not kept. */
    data class Failed(
        val reason: String,
    ) : SearchState
}

/**
 * How a question was asked (#801, #802): by itself (*Ask AI automatically*, [automatic]) or by a tap;
 * from the web ([web]) or the model's knowledge; and what it cost ([cost]), as it was asked.
 */
data class SearchWay(
    val automatic: Boolean = false,
    val web: Boolean = false,
    val cost: QuestionCost = QuestionCost(),
)

/** A search kept in the cache: its [state] as checked now, and when it was written ([writtenAt]), if the cache says. */
data class KeptSearch(
    val state: SearchState,
    val writtenAt: Instant?,
)

/**
 * *Search with AI* (#773; `design/design.md`, *Search with AI*): under the empty state of Home's
 * *Dates*, *Genres* or *Lyrics*, a tap asks the AI service ([ai]) for what the sources did not give,
 * one call ([AiSearchPrompt]), and the reply is read and checked ([answers]). Modelled on *Why is this
 * …?* ([GenreReasons]): the reply is kept in the synthesis cache ([cache]) as a card of
 * [CardKind.SEARCH], its kind's key as the card's subject, so it is found again with no call
 * ([kept]), and checked again as it is read, so a stricter check applies to what was kept before.
 *
 * Its key ([key]) is the track (as the sources name it, and its length), the kind (for *Dates*, with
 * the album as the player reports it, [subject]: a recording on its album and on a later compilation
 * has two albums' dates), the AI service, its model and effort, and the prompts' version; not the
 * evidence's fingerprint, since the panel is empty whatever the evidence says. Another service,
 * model or effort, or a new prompt, asks again. None with no AI service ready: none switched on, no
 * key, no model, or *Show insights* off, the switch that stops every AI call.
 *
 * A genre's definition ([SearchKind.DEFINITION], #786) is about the genre, not the track: its key
 * has no track ([GENRE_ENTITY], no length), and its subject is the kind and the genre's identity
 * (`GenreLabel.identity`), so one answer serves every track that has the genre, with its own
 * prompt's version ([AiSearchPrompt.version]); the AI service, model and effort as the others.
 *
 * An answer is the panel's alone: it never enters the evidence, the insight, the track's genre
 * labels, *Sources* or what is read aloud. A cache that throws is a miss, and a keep that fails
 * keeps nothing. Each state comes with its time by [clock], in whole milliseconds, as the cache
 * keeps it, so the screen and the cache count *Keep history for* from the same time.
 *
 * *AI web search* (#801, [web], the web search engine, `Settings.aiWebSearch`): the question is
 * asked as [WebQuestions] asks it, *Once* or *Up to {n} times* from web pages, or from the model's
 * knowledge with it off, without Exa web search on or an engine, and always for a genre's
 * definition. An answer from the web has its own key (`CardKey.modelKnowledge` false, its own
 * prompts' version), so the two are kept apart: one from the model's knowledge is not shown once
 * the web can be searched. It is kept with the addresses of the pages its searches found, which
 * check it again as it is read, and with how it was asked ([SearchWay]: automatically or not, what
 * it cost), in an envelope ([Kept]); a reply kept before #801 is read as it is.
 */
class AiSearches(
    private val ai: AiAccess?,
    private val cache: PipelineCache,
    private val hasher: Hasher,
    private val answers: AiSearchAnswers,
    private val clock: Clock = Clock.System,
    private val web: WebSearcher? = null,
) {
    /**
     * The key of [track]'s search of [kind] under [settings], or null when no AI service is ready;
     * for [SearchKind.DEFINITION], of [genre]'s (its identity), whatever the track, null with none.
     */
    fun key(
        track: TrackState,
        kind: SearchKind,
        settings: Settings,
        genre: String? = null,
    ): CardKey? {
        val access = ai?.takeIf { settings.writeInsights }
        val model = access?.let { settings.model(it.provider.id) }
        val aboutGenre = kind == SearchKind.DEFINITION
        val noGenre = aboutGenre && genre == null
        if (access == null || model == null || noGenre) return null
        val fromWeb = used(kind, settings) != AiWebSearch.OFF
        return CardKey(
            kind = CardKind.SEARCH,
            track = if (aboutGenre) GENRE_ENTITY else track.normalized,
            duration = if (aboutGenre) null else track.nowPlaying.duration,
            evidenceFingerprint = NO_FINGERPRINT,
            promptVersion = AiSearchPrompt.version(kind, fromWeb),
            styleHash = styleHash(hasher),
            provider = access.provider.id,
            model = model,
            // From the model's own knowledge, whatever *Model's own knowledge* says, unless from the web.
            modelKnowledge = !fromWeb,
            effort = settings.effort(access.provider.id),
            subject = subject(track, kind, genre),
        )
    }

    /**
     * How a question of [kind] searches the web under [settings] (#801): *AI web search* as used
     * (`Settings.aiWebSearchUsed`: off while Exa web search is), off with no engine ([web]) or for a
     * genre's definition, *Once* for *Up to {n} times* with a service that takes no tools.
     */
    fun used(
        kind: SearchKind,
        settings: Settings,
    ): AiWebSearch {
        val access = ai
        return if (access == null || kind == SearchKind.DEFINITION) {
            AiWebSearch.OFF
        } else {
            WebQuestions(access, web).used(settings.aiWebSearchUsed)
        }
    }

    /**
     * What [track]'s search of [kind] is about besides the track, its key's `CardKey.subject`: the
     * kind's key, and for [SearchKind.DATES] the album as the player reports it, read as album names
     * are compared ([NameMatcher.albumKey]: "Vol. 2" is "Volume 2"), after a line break (empty for
     * none), since the prompt asks when that album came out; for [SearchKind.DEFINITION], [genre]'s
     * identity after a line break (#786). The same whatever the AI service: what a stop is
     * remembered by too (`AppAiSearches`).
     */
    fun subject(
        track: TrackState,
        kind: SearchKind,
        genre: String? = null,
    ): String = subject(track, kind, albums, genre)

    // Album names compared as the panels compare them.
    private val albums = NameMatcher(answers.accentFolder)

    /** The search kept for [key], of [kind], read and checked for [track]; null when none is kept. */
    suspend fun kept(
        key: CardKey,
        kind: SearchKind,
        track: TrackState,
    ): KeptSearch? {
        val card = attempt { cache.keptCard(key) }
        val state = card?.let { stateOf(read(kind, Kept.of(it.text), track)) }
        return state?.let { KeptSearch(it, card.writtenAt) }
    }

    // What [kept] holds, read and checked for [track], with how it was asked.
    private fun read(
        kind: SearchKind,
        kept: Kept,
        track: TrackState,
    ): Pair<SearchReading, SearchWay> {
        val way = SearchWay(kept.automatic, web = kept.pages != null, QuestionCost(kept.searches, kept.calls))
        val reading =
            if (kept.pages != null && kept.reply.isEmpty()) {
                // A search that found no page: nothing found, no call made.
                SearchReading.NothingUsable
            } else {
                answers.read(kind, kept.reply, track.normalized.artists, kept.pages)
            }
        return reading to way
    }

    /**
     * Asks [key]'s search of [kind] for [track], whose [facts] the prompt gives, as [asking] says
     * (*AI web search*, #801, as [used] reads it, and whether it started by itself): each state as
     * it comes to [show] with when it came; returns the last. Each web search and AI call is counted
     * to [counted] as it is made. Before the last, [settle] says whether it still counts (not stopped
     * meanwhile): if not, nothing is shown or kept, and null is returned; if so, the last is kept and
     * shown whatever cancels the call then, so a stop that comes too late leaves the answer shown and
     * kept alike. An answer read and checked, usable or not, is kept with the time it is shown with;
     * a failure is not, a web search that failed included.
     */
    @Suppress("LongParameterList") // The search, its track and facts, how it is asked, and where its states go.
    suspend fun search(
        key: CardKey,
        kind: SearchKind,
        track: TrackState,
        facts: SearchFacts,
        show: (SearchState, Instant) -> Unit,
        settle: () -> Boolean = { true },
        asking: Asking = Asking(),
        counted: (QuestionCost) -> Unit = {},
    ): SearchState? {
        val access = checkNotNull(ai) { "no AI service" }
        val questions = WebQuestions(access, web)
        // A key from the model's knowledge asks from it, whatever the settings say since.
        val search = if (key.modelKnowledge) AiWebSearch.OFF else questions.used(asking.search)
        show(SearchState.Searching(web = search != AiWebSearch.OFF), now())
        val base = CompletionRequest(key.model, "", maxTokensFor(key.effort), key.effort)
        val reply = questions.ask(question(kind, facts), search, asking.limit, base, counted)
        val pages = reply.pages?.map(WebPage::url)
        val kept = Kept(reply.text, pages, asking.automatic, reply.cost.searches, reply.cost.calls)
        val end = reply.end
        val reading =
            when {
                reply.searchFailed -> null
                end == null && pages != null -> SearchReading.NothingUsable
                (end as? CompletionEvent.Done)?.stopReason == StopReason.END -> read(kind, kept, track).first
                else -> null
            }
        val way = SearchWay(asking.automatic, web = pages != null, reply.cost)
        val state =
            stateOf(reading?.let { it to way })
                ?: SearchState.Failed(if (reply.searchFailed) WEB_SEARCH_FAILED else failure(end, reply.text, reading))
        if (!settle()) return null
        return withContext(NonCancellable) {
            val at = now()
            if (state !is SearchState.Failed) attempt { cache.keep(key, kept.text(), at) }
            show(state, at)
            state
        }
    }

    // [kind]'s question about [facts], in each way it is asked.
    private fun question(
        kind: SearchKind,
        facts: SearchFacts,
    ): WebQuestion {
        val genres = answers.genreNames
        return object : WebQuestion {
            override val knowledge: String get() = AiSearchPrompt.build(kind, facts, genres)
            override val query: String get() = WebPrompt.query(kind, facts)

            override fun once(pages: List<WebPage>): String = WebPrompt.once(kind, facts, pages, genres)

            override fun agent(limit: Int): String = WebPrompt.agent(kind, facts, limit, genres)
        }
    }

    /**
     * How a question is asked (#801): *AI web search*'s choice ([search]) and limit ([limit]) as last
     * read, and whether it started by itself ([automatic], *Ask AI automatically*).
     */
    data class Asking(
        val search: AiWebSearch = AiWebSearch.OFF,
        val limit: Int = Settings.DEFAULT_AI_WEB_SEARCH_LIMIT,
        val automatic: Boolean = false,
    ) {
        companion object {
            /** How [settings] ask, by itself ([automatic]) or not. */
            fun of(
                settings: Settings?,
                automatic: Boolean,
            ): Asking =
                Asking(
                    settings?.aiWebSearchUsed ?: AiWebSearch.OFF,
                    settings?.aiWebSearchLimit ?: Settings.DEFAULT_AI_WEB_SEARCH_LIMIT,
                    automatic,
                )
        }
    }

    /**
     * What a question keeps in the synthesis cache (#801): its [reply]; with web search, the
     * addresses of the [pages] its searches found (none found: an empty list; from the model's
     * knowledge: null); whether it started by itself ([automatic]); and what it cost ([searches],
     * [calls]). Kept as JSON marked [MARK]; a text without the mark is a reply kept before #801,
     * from the model's knowledge, by a tap or not, which reads as one asked by a tap.
     */
    @Serializable
    internal class Kept(
        val reply: String,
        val pages: List<String>? = null,
        val automatic: Boolean = false,
        val searches: Int = 0,
        val calls: Int = 0,
        @SerialName(MARK) val mark: Int = 1,
    ) {
        fun text(): String = KEPT_JSON.encodeToString(serializer(), this)

        companion object {
            const val MARK: String = "sleevesight_kept"

            fun of(text: String): Kept {
                val marked = text.contains("\"$MARK\"")
                @Suppress("SwallowedException") // Not one: the reply as kept before.
                return try {
                    if (marked) KEPT_JSON.decodeFromString(serializer(), text) else Kept(text)
                } catch (e: IllegalArgumentException) {
                    Kept(text)
                }
            }

            private val KEPT_JSON =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
        }
    }

    // The clock's time, in whole milliseconds, as the cache keeps it.
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    // [call], or null when it throws anything but cancellation: a cache that fails is a miss.
    private suspend fun <T> attempt(call: suspend () -> T): T? =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    companion object {
        /**
         * [track]'s search of [kind]'s subject, as [subject] gives it, album names read by [albums]:
         * for what tells one search from another without the AI service (`AutoSearches`' attempts).
         */
        fun subject(
            track: TrackState,
            kind: SearchKind,
            albums: NameMatcher,
            genre: String? = null,
        ): String =
            when (kind) {
                SearchKind.DATES -> kind.key + "\n" + albums.albumKey(track.nowPlaying.album.orEmpty())
                SearchKind.GENRES, SearchKind.LYRICS -> kind.key
                SearchKind.DEFINITION -> kind.key + "\n" + genre.orEmpty()
            }

        /**
         * The entity of a genre's definition's key (#786) in the track's place: no track, so the
         * store's key is the subject alone (no track's lookup key is empty, so none can match it).
         */
        val GENRE_ENTITY: NormalizedTrack = NormalizedTrack("", emptyList(), "", null, null, emptyList(), "")

        /** Why a search failed when its reply was not the JSON object asked for. */
        const val MALFORMED: String = "malformed"

        /** Why a search failed when its web search did, *Once*'s (#801): no call was made. */
        const val WEB_SEARCH_FAILED: String = "web_search_failed"

        /** The evidence fingerprint of a search's key: none, as the panel is empty whatever the evidence. */
        const val NO_FINGERPRINT: String = ""

        /**
         * The token limit of a search at [effort]: half a card's (`CompletionRequest.maxTokensFor`),
         * as the reply is a small JSON object, while the reasoning before it still counts against the
         * limit; a model that takes no effort control gets at most the default
         * (`maxTokensSent`, `:ai`).
         */
        fun maxTokensFor(effort: ReasoningEffort?): Int = CompletionRequest.maxTokensFor(effort) / 2

        // The state [read] shows, asked as its way says; null for none read, or one that is not an answer.
        private fun stateOf(read: Pair<SearchReading, SearchWay>?): SearchState? =
            when (val reading = read?.first) {
                is SearchReading.Found -> SearchState.Answered(reading.answer, read.second)
                SearchReading.NothingUsable -> SearchState.NothingFound(read.second)
                SearchReading.Malformed, null -> null
            }

        // Why a reply that is no answer failed: the service's error, a refusal, no text, a reply
        // cut off, or one that is not the JSON object asked for.
        private fun failure(
            end: CompletionEvent?,
            reply: String,
            reading: SearchReading?,
        ): String =
            when {
                end is CompletionEvent.Error -> ReplyFailures.of(end)
                end !is CompletionEvent.Done -> ReplyFailures.INCOMPLETE
                end.stopReason == StopReason.REFUSAL -> ReplyFailures.DECLINED
                else ->
                    ReplyFailures.ofBlank(end, reply)
                        ?: if (reading == null) ReplyFailures.INCOMPLETE else MALFORMED
            }
    }
}
