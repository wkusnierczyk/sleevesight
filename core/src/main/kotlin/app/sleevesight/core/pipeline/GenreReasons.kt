package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GenreDispute
import app.sleevesight.core.synthesis.GenreReasonPrompt
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.text.DateForm
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** A reason kept in the cache: its [state] as checked, and when it was written ([writtenAt]), if the cache says. */
data class KeptReason(
    val state: InsightState,
    val writtenAt: Instant?,
)

/** What a tap on *Why is this …?* does for a genre (#762), as [GenreReasons.path] works it out. */
sealed interface ReasonPath {
    /** Not offered: *Show insights* off, the evidence not in, or a genre the track does not have. */
    data object None : ReasonPath

    /** No AI service ready (none switched on, no key, or no model): disabled, with where to enable one. */
    data object NoService : ReasonPath

    /** Only MusicBrainz gives the genre, and the AI's genre list cannot run ([GenreLists.mode] none). */
    data object CannotList : ReasonPath

    /** Only MusicBrainz gives the genre, and the AI's genre list, known, does not have it: no call. */
    data object NotListed : ReasonPath

    /** Path B: only MusicBrainz gives the genre, and the AI's genre list, not known yet, is asked first, by [key]. */
    data class Listing(
        val key: CardKey,
    ) : ReasonPath

    /** Path A: the reason, by [key]. */
    data class Reason(
        val key: CardKey,
    ) : ReasonPath
}

/**
 * *Why is this …?* in a genre's panel (#523; `design/design.md`, *Genre labels*): why the track
 * carries a genre label, written on a tap from its evidence by the AI service ([ai]), one call, or
 * a cascade of two with *AI web search* (#801, #695: [write]), through the insight's grounding
 * check, and kept in the synthesis cache ([cache]) as a card of [CardKind.GENRE], keyed like the
 * insight ([key]: the track, the evidence's fingerprint, the prompt version, the AI service, its
 * model and effort, the model-knowledge switch) and the genre ([CardKey.subject]). A reason once
 * written is found again with no call ([found]) for as long as its key holds: a settings change
 * that would offer the insight's *Rewrite insight* (another service, model or effort, new
 * evidence) misses it, and *Why is this …?* is offered again.
 *
 * Its states are the insight's ([InsightState]): [InsightState.Writing] while the reply comes,
 * [InsightState.Written] once checked, [InsightState.NoEvidence] when the evidence has nothing to
 * narrate (no call), the model replies `NO_EVIDENCE`, or its reply disputes the label ([GenreDispute],
 * #853), [InsightState.Failed] when the call fails or is declined. Not written ([key] null) with
 * *Show insights* off, with no AI service ready, before the evidence is in, or for a genre only
 * MusicBrainz's tags give (D12: they never reach a prompt) and the AI's genre list does not have:
 * for such a genre the list is asked first, naming no genre ([GenreLists], #762), and a genre on it
 * is written as any other. What a tap does, and what the
 * panel says where it does nothing, is [path].
 *
 * A cache that throws is a miss, and a write that fails keeps nothing; neither stops the reason.
 * Each state written comes with its time by [clock]; a reply kept is kept with the time it is shown
 * with, so the screen and the cache count *Keep history for* from the same time, as the insight's
 * (#643). The prompt asks for dates in the form [dates] gives as it is built (#957), which no key holds.
 */
@Suppress("TooManyFunctions") // Each step of a reason: its key, its path, its calls with and without the web.
class GenreReasons(
    private val ai: AiAccess?,
    private val cache: PipelineCache,
    private val hasher: Hasher,
    private val clock: Clock = Clock.System,
    private val web: WebSearcher? = null,
    private val dates: () -> DateForm = { DateForm.APP },
) {
    /**
     * The key of [genre]'s reason for [track] under [settings] ([GenreLabel.identity]), or null when
     * none may be written: see the class. [listed] holds the genres of the AI's list for the track,
     * by identity, where it is known (#762): a genre on it may reach a prompt, as one a source other
     * than MusicBrainz gives may.
     */
    fun key(
        track: TrackState,
        genre: String,
        settings: Settings,
        listed: Set<String>? = null,
    ): CardKey? {
        val access = ready(track, settings)?.takeIf { prompted(track, genre) || listed?.contains(genre) == true }
        val model = access?.let { settings.model(it.provider.id) }
        val evidence = track.evidence
        if (access == null || model == null || evidence == null) return null
        // With *AI web search* used (#801), a key of its own: a reason from the web is kept apart
        // from one from the evidence alone, which stays under the key without the web part
        // ([found]); the keys from before stay as they were.
        val fromWeb = WebQuestions(access, web).used(settings.aiWebSearchUsed) != AiWebSearch.OFF
        val subject = if (fromWeb) genre + WEB else genre
        return CardKey(
            kind = CardKind.GENRE,
            entity = CardEntity.Track(track.normalized, track.nowPlaying.duration, subject),
            evidenceFingerprint = evidence.fingerprint,
            promptVersion = GenreReasonPrompt.VERSION,
            styleHash = styleHash(hasher),
            provider = access.provider.id,
            model = model,
            modelKnowledge = settings.isEnabled(SourceId.MODEL),
            effort = settings.effort(access.provider.id),
        )
    }

    /**
     * What a tap on *Why is this …?* does for [track]'s genre [genre] under [settings] (#762), the
     * AI's genre list for the track ([lists]) known as [listed] (by identity; null while not known):
     *
     * - nothing ([ReasonPath.None]) with *Show insights* off, before the evidence is in, or for a
     *   genre the track does not have;
     * - with no AI service ready (none switched on, no key, no model), nothing, and the panel says
     *   where one is enabled ([ReasonPath.NoService]);
     * - path A ([ReasonPath.Reason]): the reason, for a genre a source other than MusicBrainz gives,
     *   or one on the AI's list;
     * - path B ([ReasonPath.Listing]): for a genre only MusicBrainz gives, the AI's list first, while
     *   it is not known and it can run; [ReasonPath.CannotList] when it cannot ([GenreLists.mode]);
     *   once known without the genre, [ReasonPath.NotListed], and no call.
     */
    fun path(
        track: TrackState,
        genre: String,
        settings: Settings,
        lists: GenreLists,
        listed: Set<String>?,
    ): ReasonPath {
        val key = key(track, genre, settings, listed)
        return when {
            !offered(track, settings) || track.genres.all.none { it.genre.identity == genre } -> ReasonPath.None
            key != null -> ReasonPath.Reason(key)
            ready(track, settings) == null -> ReasonPath.NoService
            listed != null -> ReasonPath.NotListed
            else -> lists.key(track, settings)?.let(ReasonPath::Listing) ?: ReasonPath.CannotList
        }
    }

    // The AI service, when one with a model is ready under [settings] and [track]'s evidence is in;
    // what [key] and [path] ask for besides the genre's sources.
    private fun ready(
        track: TrackState,
        settings: Settings,
    ): AiAccess? = ai?.takeIf { offered(track, settings) && settings.model(it.provider.id) != null }

    /**
     * The reason kept for [key], checked against [track]'s evidence, with when it was written where
     * the cache says; null when none is kept. One asked again with web search (#801) is checked
     * against the track's evidence with the pages it found, numbered as they were.
     */
    suspend fun kept(
        key: CardKey,
        track: TrackState,
    ): KeptReason? {
        val card = attempt { cache.keptCard(key) } ?: return null
        val searched = KeptReasonText.of(card.text)
        val evidence =
            searched?.let { kept ->
                track.evidence?.let { own ->
                    ReasonQuestion.withPages(own, kept.pages.map(KeptPage::page), hasher, card.writtenAt ?: now())
                }
            }
        val text = searched?.reply ?: card.text
        val state = checked(text, key, track, cached = true, usage = null, complete = true, evidence)
        return KeptReason(state, card.writtenAt)
    }

    /**
     * The reason to show for [key] with no call: the one kept for it ([kept]); or, for a key that may
     * search the web (#801), the one written from the evidence alone, kept under the key without its
     * web part ([alone]), so a change to *AI web search* does not pay for the same question again,
     * where it stands: it cites a source (#695, [stands]). One from the model's own knowledge alone
     * is not shown before the web is asked; once it was, and the web explained nothing, it is shown
     * in place of what the web gave ([settled]), as [write] shows it. Null when nothing is kept.
     */
    suspend fun found(
        key: CardKey,
        track: TrackState,
    ): KeptReason? {
        val own = kept(key, track)
        val alone = alone(key).takeIf { it != key } ?: return own
        val first = kept(alone, track)?.takeIf { it.state is InsightState.Written }
        val shown = own?.let { settled(it.state, first?.state) }
        return when {
            own == null -> first?.takeIf { stands(it.state) }
            first != null && shown === first.state -> first
            else -> KeptReason(checkNotNull(shown), own.writtenAt)
        }
    }

    /**
     * Writes [key]'s reason for [track], one call or two, each state as it comes to [show] with when it
     * came (by the clock, in whole milliseconds as the cache keeps it); returns the last. The genre's
     * label is [label] (`GenreLabel.label`, as the chip shows it). A complete reply is kept in the
     * cache, `NO_EVIDENCE` too, so a tap on the same reason asks nothing again, with the one time it
     * is shown with (`PipelineCache.keep` with `writtenAt`). The call from the evidence alone is kept
     * under the key without its web part ([alone]), whatever *AI web search* says.
     *
     * A key that may search the web (#801, made with *AI web search* on: *Once* or *Up to 3 times*,
     * with a web search engine, [web]) searches as [asking] says, *Once* when it says *Off* since the
     * key was made. For such a key, a reason from the evidence alone kept and still [fresh] (not past
     * *Keep history for*) is shown with no call where it [stands]. The cascade (#695): when the
     * evidence cannot support the genre (nothing to narrate, which with the model's knowledge on and
     * the artists known is never so, or the model replies `NO_EVIDENCE`, now
     * or kept), or the reason cites no source, only the model's own knowledge, the same question is
     * asked again with web search ([ReasonQuestion], path A of #762's pick): what it finds is checked
     * against the track's evidence with its pages, and kept with them; a question whose searches all
     * failed is a failure, not kept. Where the web explains nothing (no page found, `NO_EVIDENCE`, or
     * a reason from the knowledge alone again), the first call's reason from the knowledge is shown,
     * if there was one ([settled]). While the web may follow, the first call's text is not shown as
     * it comes until it cites a source, so "Looking for an explanation…" covers every step. Each
     * search and call is counted to [counted].
     */
    @Suppress("LongParameterList") // The reason, its track and label, where its states go, and how it searches.
    suspend fun write(
        key: CardKey,
        track: TrackState,
        label: String,
        asking: AiSearches.Asking = AiSearches.Asking(),
        counted: (QuestionCost) -> Unit = {},
        fresh: (Instant?) -> Boolean = { true },
        show: (InsightState, Instant) -> Unit,
    ): InsightState {
        val access = checkNotNull(ai) { "no AI service" }
        val evidence = checkNotNull(track.evidence) { "no evidence" }
        val alone = alone(key)
        val search = searchOf(access, key, asking.search)
        val searches = search != AiWebSearch.OFF
        val narrate = narrates(evidence, key.modelKnowledge, artistsKnown = track.normalized.artists.isNotEmpty())
        // The evidence alone, under the key with no web part: a reason written from it that stands
        // is shown, with no call; once it could not support the genre, or was from the model's
        // knowledge alone, a tap that searches goes straight to the search, and a failed search
        // repays no call.
        val before = if (searches) kept(alone, track)?.takeIf { fresh(it.writtenAt) } else null
        if (before != null && stands(before.state)) return before.state.also { show(it, before.writtenAt ?: now()) }
        val first =
            if (narrate && before == null) {
                firstCall(access, alone, track, label, evidence, searches, counted, show)
            } else {
                null
            }
        return when {
            first != null && !goesOn(first.state, searches) -> first.state
            !searches -> InsightState.NoEvidence.also { show(it, now()) }
            else -> {
                val fallback = (first?.state ?: before?.state) as? InsightState.Written
                searchAgain(key, track, label, evidence, asking.copy(search = search), counted, fallback, show)
            }
        }
    }

    // Whether the first call is made from [evidence]: as for the insight (`EvidenceSet.nothingToNarrate`),
    // but with the model's own knowledge on ([allowModel]) and the artists known, a set with no line
    // is asked too (#695): the reason may be written from the knowledge alone, which costs no more.
    private fun narrates(
        evidence: EvidenceSet,
        allowModel: Boolean,
        artistsKnown: Boolean,
    ): Boolean = (allowModel && artistsKnown) || !evidence.nothingToNarrate(allowModel, artistsKnown)

    // Whether the cascade goes on to the web after the first call's [state] (#695): with web search
    // ([searches]), for `NO_EVIDENCE`, and for a reason that does not [stand][stands]; not for a failure.
    private fun goesOn(
        state: InsightState,
        searches: Boolean,
    ): Boolean = searches && (state == InsightState.NoEvidence || (state is InsightState.Written && !stands(state)))

    // The reason's one call from the track's [evidence], under [key], the key without its web part:
    // kept whatever it says, and shown, unless the web is searched next ([searches], [goesOn]); while
    // it may be, its text is shown as it comes only once it cites a source.
    @Suppress("LongParameterList") // As [write]'s, with the service and the evidence.
    private suspend fun firstCall(
        access: AiAccess,
        key: CardKey,
        track: TrackState,
        label: String,
        evidence: EvidenceSet,
        searches: Boolean,
        counted: (QuestionCost) -> Unit,
        show: (InsightState, Instant) -> Unit,
    ): ReplyOutcome {
        val prompt = GenreReasonPrompt.user(track.nowPlaying, track.normalized, evidence, label)
        show(InsightState.Writing(""), now())
        val request =
            CompletionRequest(
                key.model,
                prompt,
                CompletionRequest.maxTokensFor(key.effort),
                key.effort,
                system = GenreReasonPrompt.system(key.modelKnowledge, dates()),
            )
        counted(QuestionCost.CALL)
        val shows: (String) -> Boolean =
            if (searches) {
                { partial -> citesSource(partial, evidence, key.modelKnowledge) }
            } else {
                { true }
            }
        val (text, end) = stream(access, request, shows, show)
        val at = now()
        val first = outcome(end, text, key, track, evidence)
        if (first.kept) attempt { cache.keep(key, keptText(text, first.state), at) }
        if (!goesOn(first.state, searches)) show(first.state, at)
        return first
    }

    // [key]'s reason asked again with web search (#801), from [evidence] and the pages found; where
    // the web explains nothing, the first call's reason from the model's knowledge ([fallback], #695).
    @Suppress("LongParameterList") // As [write]'s, with the track's evidence and the first call's reason.
    private suspend fun searchAgain(
        key: CardKey,
        track: TrackState,
        label: String,
        evidence: EvidenceSet,
        asking: AiSearches.Asking,
        counted: (QuestionCost) -> Unit,
        fallback: InsightState.Written?,
        show: (InsightState, Instant) -> Unit,
    ): InsightState {
        val access = checkNotNull(ai)
        val question = ReasonQuestion(track, label, evidence, key.modelKnowledge, hasher, now(), dates())
        show(InsightState.Writing(""), now())
        val base = CompletionRequest(key.model, "", CompletionRequest.maxTokensFor(key.effort), key.effort)
        val reply = WebQuestions(access, web).ask(question, asking.search, asking.limit, base, counted)
        val at = now()
        val pages = question.pages.map(KeptPage::of)
        val result =
            when {
                reply.searchFailed -> ReplyOutcome(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), kept = false)
                // No page found, so no call: the evidence still cannot support it.
                reply.end == null -> ReplyOutcome(InsightState.NoEvidence, kept = true)
                // The pages as kept: retrieved when the reason is written, as a read has them. An
                // answer citing no source or page explains nothing the web found (#695): `NO_EVIDENCE`,
                // shown and kept as one.
                else ->
                    outcome(
                        reply.end,
                        reply.text,
                        key,
                        track,
                        ReasonQuestion.withPages(evidence, question.pages, hasher, at),
                    ).fromWeb()
            }
        if (result.kept) {
            val text = if (reply.end == null) GroundingCheck.NO_EVIDENCE else keptText(reply.text, result.state)
            attempt { cache.keep(key, KeptReasonText(text, pages).text(), at) }
        }
        return settled(result.state, fallback).also { show(it, at) }
    }

    // One call of [request]: its text, each piece shown as it comes while it may not be
    // `NO_EVIDENCE` and [shows] it (once it does, from then on), and its end.
    private suspend fun stream(
        access: AiAccess,
        request: CompletionRequest,
        shows: (String) -> Boolean,
        show: (InsightState, Instant) -> Unit,
    ): Pair<String, CompletionEvent?> {
        val text = StringBuilder()
        var end: CompletionEvent? = null
        var showing = false
        access.provider.stream(request, access.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> {
                    text.append(event.text)
                    val partial = text.toString()
                    showing = showing || shows(partial)
                    if (showing && !GroundingCheck.mayBeNoEvidence(partial)) {
                        show(InsightState.Writing(partial), now())
                    }
                }

                // Done or Error: no tools are sent, so never a tool call.
                else -> {
                    end = event
                }
            }
        }
        return text.toString() to end
    }

    // A reply's state, and whether it is kept: [end] and [text] read as a reason of [key], checked
    // against [evidence].
    private fun outcome(
        end: CompletionEvent?,
        text: String,
        key: CardKey,
        track: TrackState,
        evidence: EvidenceSet,
    ): ReplyOutcome =
        when (end) {
            is CompletionEvent.Done -> {
                val blank = ReplyFailures.ofBlank(end, text)
                when {
                    end.stopReason == StopReason.REFUSAL -> ReplyOutcome(InsightState.Failed(DECLINED), kept = false)
                    // No text is no reason to show, nor to keep (#665).
                    blank != null -> ReplyOutcome(InsightState.Failed(blank), kept = false)
                    else -> {
                        val complete = end.stopReason == StopReason.END
                        val searched = evidence.takeIf { it != track.evidence }
                        ReplyOutcome(
                            checked(text, key, track, cached = false, usage = end.usage, complete, searched),
                            complete,
                        )
                    }
                }
            }

            is CompletionEvent.Error -> ReplyOutcome(InsightState.Failed(ReplyFailures.of(end)), kept = false)
            else -> ReplyOutcome(InsightState.Failed(INCOMPLETE), kept = false)
        }

    // [partial], a reply still coming, as far as it has come: whether a sentence of it cites one of
    // [evidence]'s lines, so the reply will stand (#695, [stands]) whatever follows.
    private fun citesSource(
        partial: String,
        evidence: EvidenceSet,
        allowModel: Boolean,
    ): Boolean {
        val reply = GroundingCheck.check(partial, evidence, allowModel)
        return reply is InsightReply.Written && reply.grounded.citesSource
    }

    // What is kept of a complete reply [text] read as [state]: `NO_EVIDENCE` for one read as none, a
    // reply that disputes the label included (#853), so a later read does not depend on how the
    // genre is spelled then.
    private fun keptText(
        text: String,
        state: InsightState,
    ): String = if (state == InsightState.NoEvidence) GroundingCheck.NO_EVIDENCE else text

    // [this], a reply asked again with web search, as the cascade reads it (#695): one that cites no
    // source or page, only the model's knowledge, or nothing, is `NO_EVIDENCE`, kept as one.
    private fun ReplyOutcome.fromWeb(): ReplyOutcome =
        if (state is InsightState.Written && !stands(state)) copy(state = InsightState.NoEvidence) else this

    // A reply's [state], and whether its text is [kept].
    private data class ReplyOutcome(
        val state: InsightState,
        val kept: Boolean,
    )

    // How [key]'s reason searches (#801), from the key, not the settings read since ([asked]): a key
    // that may search does, *Once* when they say *Off* (as `GenreLists.ask`); one that may not never does.
    private fun searchOf(
        access: AiAccess,
        key: CardKey,
        asked: AiWebSearch,
    ): AiWebSearch =
        if (alone(key) == key) {
            AiWebSearch.OFF
        } else {
            WebQuestions(access, web).used(asked).takeUnless { it == AiWebSearch.OFF } ?: AiWebSearch.ONCE
        }

    // [key] without its web part (#801): the key of a reason from the evidence alone.
    private fun alone(key: CardKey): CardKey = key.about(key.subject?.removeSuffix(WEB))

    // The clock's time, in whole milliseconds, as the cache keeps it.
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    @Suppress("LongParameterList") // The reply, its key, its track, how it came, and what it searched.
    private fun checked(
        text: String,
        key: CardKey,
        track: TrackState,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
        searched: EvidenceSet? = null,
    ): InsightState {
        val evidence = searched ?: track.evidence ?: return InsightState.NoEvidence
        return when (val reply = GroundingCheck.check(text, evidence, key.modelKnowledge)) {
            InsightReply.NoEvidence -> {
                InsightState.NoEvidence
            }

            // A reply that says the track is not the genre explains nothing (#853), kept or new; read
            // as shown, without a draft the model withdrew.
            is InsightReply.Written -> {
                if (GenreDispute.disputes(reply.grounded.text, forms(key, track), names(track))) {
                    InsightState.NoEvidence
                } else {
                    val by = WrittenBy(key.promptVersion, key.provider, key.model, key.effort)
                    InsightState.Written(reply.grounded, cached, usage, complete, writtenBy = by, searched = searched)
                }
            }
        }
    }

    // The forms the genre of [key] is written in, folded (`GenreDispute.fold`): its identity, and its
    // label where [track]'s genres have it.
    private fun forms(
        key: CardKey,
        track: TrackState,
    ): Set<String> {
        val genre = alone(key).subject ?: return emptySet()
        val label =
            track.genres.all
                .firstOrNull { it.genre.identity == genre }
                ?.genre
                ?.label
        return setOfNotNull(genre, label?.let(GenreDispute::fold))
    }

    // [track]'s names as a reply may quote them, left out of the dispute check: a title such as "No
    // Pop" holds a negation before a genre.
    private fun names(track: TrackState): List<String> =
        listOfNotNull(
            track.nowPlaying.title,
            track.nowPlaying.album,
            track.nowPlaying.artists,
            track.normalized.title,
            track.normalized.primary,
        ) + track.normalized.artists

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
         * Whether a reason [state] stands, and goes no further down the cascade (#695): written, and
         * citing a source or a web page (`GroundedText.citesSource`), read from its citations, not
         * its words. One from the model's own knowledge alone, or `NO_EVIDENCE`, does not.
         */
        fun stands(state: InsightState): Boolean = state is InsightState.Written && state.grounded.citesSource

        /**
         * What the cascade shows (#695) of the web's answer [web] (the reason asked again with web
         * search, a failure included) and the first call's [first], from the evidence and the model's
         * knowledge: the web's where it stands or failed; else the first's from the model's
         * knowledge, if there was one; else `NO_EVIDENCE`: an answer from the web that cites no
         * source or page (one kept from before this was read so too) explains nothing.
         */
        fun settled(
            web: InsightState,
            first: InsightState?,
        ): InsightState =
            when {
                stands(web) || web is InsightState.Failed -> web
                first is InsightState.Written && first.grounded.fromModelAlone -> first
                else -> InsightState.NoEvidence
            }

        /** What a reason's key that may search the web (#801) has after the genre, in its subject. */
        const val WEB: String = "\nweb"

        /** Why a reason failed when the model declined. */
        const val DECLINED: String = ReplyFailures.DECLINED

        /** Why a reason failed when its reply never ended. */
        const val INCOMPLETE: String = ReplyFailures.INCOMPLETE

        /**
         * Whether [track]'s genre [genre] may reach a prompt: a source other than MusicBrainz gives it,
         * on some row, or the AI's genre list does (`TrackLabel.aiModel`, #762: [track]'s genres with
         * the list, `TrackGenres.withAi`). D12: MusicBrainz's tags, CC BY-NC-SA 3.0, never reach a prompt.
         */
        fun prompted(
            track: TrackState,
            genre: String,
        ): Boolean =
            track.genres.rows
                .flatMap { it.labels }
                .any { label ->
                    label.genre.identity == genre &&
                        (label.aiModel != null || label.credits.any { it.source != LabelSource.MUSICBRAINZ })
                }

        /**
         * Whether *Why is this …?* is offered at all for [track] under [settings]: *Show insights* on
         * (the switch that stops every AI call), and the evidence in, every source answered.
         */
        fun offered(
            track: TrackState,
            settings: Settings,
        ): Boolean = settings.writeInsights && track.gathered && track.evidence != null
    }
}
