package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.AiSearchPrompt
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import app.sleevesight.core.synthesis.SearchReading
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** A search of *Search with AI* (#773), as its panel shows it. */
sealed interface SearchState {
    /** The call is under way: the turning record and "Searching with AI…", with *Stop*. */
    data object Searching : SearchState

    /** The answer, as checked. */
    data class Answered(
        val answer: SearchAnswer,
    ) : SearchState

    /** The answer had nothing usable: empty, or nothing in it passed the checks. Kept, as an answer is. */
    data object NothingFound : SearchState

    /** The call failed, for [reason] (a `ReplyFailures` label, or [AiSearches.MALFORMED]); not kept. */
    data class Failed(
        val reason: String,
    ) : SearchState
}

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
 * An answer is the panel's alone: it never enters the evidence, the insight, the track's genre
 * labels, *Sources* or what is read aloud. A cache that throws is a miss, and a keep that fails
 * keeps nothing. Each state comes with its time by [clock], in whole milliseconds, as the cache
 * keeps it, so the screen and the cache count *Keep history for* from the same time.
 */
class AiSearches(
    private val ai: AiAccess?,
    private val cache: PipelineCache,
    private val hasher: Hasher,
    private val answers: AiSearchAnswers,
    private val clock: Clock = Clock.System,
) {
    /** The key of [track]'s search of [kind] under [settings], or null when no AI service is ready. */
    fun key(
        track: TrackState,
        kind: SearchKind,
        settings: Settings,
    ): CardKey? {
        val access = ai?.takeIf { settings.writeInsights }
        val model = access?.let { settings.model(it.provider.id) }
        if (access == null || model == null) return null
        return CardKey(
            kind = CardKind.SEARCH,
            track = track.normalized,
            duration = track.nowPlaying.duration,
            evidenceFingerprint = NO_FINGERPRINT,
            promptVersion = AiSearchPrompt.VERSION,
            styleHash = styleHash(hasher),
            provider = access.provider.id,
            model = model,
            // The model answers from its own knowledge, whatever *Model's own knowledge* says: fixed.
            modelKnowledge = true,
            effort = settings.effort(access.provider.id),
            subject = subject(track, kind),
        )
    }

    /**
     * What [track]'s search of [kind] is about besides the track, its key's `CardKey.subject`: the
     * kind's key, and for [SearchKind.DATES] the album as the player reports it, read as album names
     * are compared ([NameMatcher.albumKey]: "Vol. 2" is "Volume 2"), after a line break (empty for
     * none), since the prompt asks when that album came out. The same whatever the AI service: what
     * a stop is remembered by too (`AppAiSearches`).
     */
    fun subject(
        track: TrackState,
        kind: SearchKind,
    ): String = subject(track, kind, albums)

    // Album names compared as the panels compare them.
    private val albums = NameMatcher(answers.accentFolder)

    /** The search kept for [key], of [kind], read and checked for [track]; null when none is kept. */
    suspend fun kept(
        key: CardKey,
        kind: SearchKind,
        track: TrackState,
    ): KeptSearch? {
        val card = attempt { cache.keptCard(key) }
        val state = card?.let { stateOf(answers.read(kind, it.text, track.normalized.artists)) }
        return state?.let { KeptSearch(it, card.writtenAt) }
    }

    /**
     * Asks [key]'s search of [kind] for [track], whose [facts] the prompt gives, one call: each state
     * as it comes to [show] with when it came; returns the last. Before the last, [settle] says
     * whether it still counts (not stopped meanwhile): if not, nothing is shown or kept, and null is
     * returned; if so, the last is kept and shown whatever cancels the call then, so a stop that
     * comes too late leaves the answer shown and kept alike. An answer read and checked, usable or
     * not, is kept with the time it is shown with; a failure is not.
     */
    @Suppress("LongParameterList") // The search, its track and facts, and where its states go.
    suspend fun search(
        key: CardKey,
        kind: SearchKind,
        track: TrackState,
        facts: SearchFacts,
        show: (SearchState, Instant) -> Unit,
        settle: () -> Boolean = { true },
    ): SearchState? {
        val access = checkNotNull(ai) { "no AI service" }
        show(SearchState.Searching, now())
        val request =
            CompletionRequest(
                key.model,
                AiSearchPrompt.build(kind, facts, answers.genreNames),
                maxTokensFor(key.effort),
                key.effort,
            )
        val text = StringBuilder()
        var end: CompletionEvent? = null
        access.provider.stream(request, access.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> text.append(event.text)
                is CompletionEvent.Done, is CompletionEvent.Error -> end = event
            }
        }
        val reply = text.toString()
        val reading =
            (end as? CompletionEvent.Done)?.takeIf { it.stopReason == StopReason.END }?.let {
                answers.read(kind, reply, track.normalized.artists)
            }
        val state = stateOf(reading) ?: SearchState.Failed(failure(end, reply, reading))
        if (!settle()) return null
        return withContext(NonCancellable) {
            val at = now()
            if (state !is SearchState.Failed) attempt { cache.keep(key, reply, at) }
            show(state, at)
            state
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
        ): String =
            when (kind) {
                SearchKind.DATES -> kind.key + "\n" + albums.albumKey(track.nowPlaying.album.orEmpty())
                SearchKind.GENRES, SearchKind.LYRICS -> kind.key
            }

        /** Why a search failed when its reply was not the JSON object asked for. */
        const val MALFORMED: String = "malformed"

        /** The evidence fingerprint of a search's key: none, as the panel is empty whatever the evidence. */
        const val NO_FINGERPRINT: String = ""

        /**
         * The token limit of a search at [effort]: half a card's (`CompletionRequest.maxTokensFor`),
         * as the reply is a small JSON object, while the reasoning before it still counts against the
         * limit; a model that takes no effort control gets at most the default
         * (`maxTokensSent`, `:ai`).
         */
        fun maxTokensFor(effort: ReasoningEffort?): Int = CompletionRequest.maxTokensFor(effort) / 2

        // The state [reading] shows; null for none read, or one that is not an answer.
        private fun stateOf(reading: SearchReading?): SearchState? =
            when (reading) {
                is SearchReading.Found -> SearchState.Answered(reading.answer)
                SearchReading.NothingUsable -> SearchState.NothingFound
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
