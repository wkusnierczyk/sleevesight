package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.SearchState
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.pastKeepHistory
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** *Ask AI* under an empty panel on Home (#773; *Search with AI* until #802), as the panel shows it. */
sealed interface AiSearch {
    /**
     * Not offered: no searches here (previews, tests), before the pipeline and the settings are in,
     * or with *Show insights* off, which stops every AI call (as *Why is this …?* is not offered then).
     */
    data object None : AiSearch

    /** Offered, disabled: no AI service ready (none switched on, no key, or no model). */
    data object Unready : AiSearch

    /** Offered: a tap asks the AI service, a paid call. */
    data object Offered : AiSearch

    /**
     * Under way: the turning record and "Searching the web with AI…", or from the model's knowledge
     * ([web] false) "Asking AI…", and *Stop*.
     */
    data class Searching(
        val web: Boolean = false,
    ) : AiSearch

    /** Answered by [model], as checked, asked as [way] says (#801, #802). */
    data class Answered(
        val answer: SearchAnswer,
        val model: String,
        val way: SearchWay = SearchWay(),
    ) : AiSearch

    /**
     * Answered by [model] with nothing usable: "{model} found nothing on the web.", or from its
     * knowledge "{model} couldn't say from its own knowledge.", asked as [way] says.
     */
    data class NothingFound(
        val model: String,
        val way: SearchWay = SearchWay(),
    ) : AiSearch

    /** The call failed, for [reason]; offered again. */
    data class Failed(
        val reason: String,
    ) : AiSearch
}

/**
 * *Search with AI* under the empty state of Home's *Dates*, *Genres* or *Lyrics* (#773): each
 * panel's search, by its [SearchKind], for a track; and under a genre with no definition from
 * Wikipedia (#786), [SearchKind.DEFINITION], of a genre (each method's `genre`, its identity), the
 * same whatever the track.
 */
interface AiSearchFeed {
    /** [track]'s search of [kind] (for [SearchKind.DEFINITION], [genre]'s), as it changes. */
    fun search(
        track: TrackState,
        kind: SearchKind,
        genre: String? = null,
    ): Flow<AiSearch>

    /**
     * A tap on *Search with AI* or *Retry*, or, [automatic], the search starting by itself (*Search
     * with AI*, the setting, [AutoSearches]): [track]'s search of [kind] asks about [facts], unless it
     * is under way, answered or kept, or, [automatic], once stopped (*Stop* is the user's "no" to it).
     * Returns whether that is settled: the search asked, under way, answered, kept or declined; false
     * with no AI service ready, or with *Show insights* off. For [SearchKind.DEFINITION], [genre]'s.
     */
    fun ask(
        track: TrackState,
        kind: SearchKind,
        facts: SearchFacts,
        automatic: Boolean = false,
        genre: String? = null,
    ): Boolean

    /**
     * A tap on *Stop*: [track]'s search of [kind] (for [SearchKind.DEFINITION], [genre]'s) is
     * cancelled, and nothing from it shown or kept.
     */
    fun stop(
        track: TrackState,
        kind: SearchKind,
        genre: String? = null,
    )

    /** No search: the panels show their empty states alone (previews, tests). */
    object None : AiSearchFeed {
        override fun search(
            track: TrackState,
            kind: SearchKind,
            genre: String?,
        ): Flow<AiSearch> = flowOf(AiSearch.None)

        override fun ask(
            track: TrackState,
            kind: SearchKind,
            facts: SearchFacts,
            automatic: Boolean,
            genre: String?,
        ) = false

        override fun stop(
            track: TrackState,
            kind: SearchKind,
            genre: String?,
        ) = Unit
    }
}

/**
 * The app's [AiSearchFeed] (#773), as *Why is this …?* is the app's ([AppGenreNotes]): searches made
 * with the live pipeline's [parts] (its AI service, synthesis cache and hasher, [AiSearches]) under
 * the [settings] as last read, their answers read and checked by [answers], the model named as the
 * AI settings name it ([modelName]: as the service's listing named it, else `ModelNames`, else its id).
 *
 * A search is kept with the track: in memory, by its key (the track, the kind, the AI service, model
 * and effort, the prompts' version; a genre's definition, #786, with the genre in the track's place,
 * `AiSearches.key`), so it shows again with no call when the panel is closed and
 * opened or the page left; and in the synthesis cache, read the first time a panel asks for it
 * ([search]), and again before any call ([ask]), so it shows again when the track plays later, and a
 * search that starts by itself never pays for an answer kept. Each is kept with when it was written
 * (by [clock], or as the cache says), and shown no longer than *Keep history for* after it, as the
 * cache serves it; past it, it is dropped and offered again. A failure is shown, not kept: offered
 * again beside it.
 *
 * The call runs in [scope], the process's, so leaving the panel or the page does not stop a call
 * paid for; *Stop* ([stop]) does, and nothing from it is shown or kept: a stop that comes once the
 * answer has settled leaves it. A stop is the user's "no" to that panel of that track: remembered in
 * memory (the track, its length and the key's subject, so whatever AI service; the last few hundred),
 * a search stopped, asked by a tap or by itself, is not asked by itself again ([ask]'s `automatic`),
 * the track coming back included, for as long as the process lives; a tap asks it, and lifts the "no".
 * A search asked by itself ([ask]'s `automatic`) checks its gates again once the cache has missed,
 * just before the paid call: [automaticGate] of its track, kind and genre (the app on screen, the
 * setting on, as the `AutoSearches` that started it last read them: the same reading that starts it,
 * so the two cannot disagree; the panels' own, or the genres' definitions', #786, which also asks
 * that the genre still has no definition, settled on this play, with *Show genres* and Wikipedia
 * on), and its key under the settings as last read (*Show insights* on, the AI service ready, the
 * same model and effort). If one no longer holds, it asks nothing and shows nothing, and is handed
 * back to [deferred] with its kind and genre, once it is no longer under way (so a start at once is
 * not taken for it), to start later as if it had not been tried (off screen, on return). A tap is
 * gated by none of these: not the app's visibility, the setting, nor a genre's definition.
 * Each search is marked with [epoch], and after *Clear history* ([forget]) nothing it finds is shown
 * or kept: a state is shown only under the lock [forget] clears under ([ShownSearches.publish]), so
 * one that passed the check cannot land after the clear. *Clear history* forgets the stops too.
 */
@Suppress("LongParameterList") // Where searches are made and checked, where they run, the names, and the time.
class AppAiSearches(
    private val parts: StateFlow<PipelineParts?>,
    private val settings: StateFlow<Settings?>,
    private val answers: AiSearchAnswers,
    private val scope: CoroutineScope,
    private val epoch: ClearEpoch = ClearEpoch(),
    private val modelName: (String) -> String = { ModelNames.of(it) ?: it },
    private val clock: Clock = Clock.System,
    private val automaticGate: (TrackState, SearchKind, String?) -> Boolean = { _, _, _ -> true },
    private val deferred: (TrackState, SearchKind, String?) -> Unit = { _, _, _ -> },
) : AiSearchFeed {
    // A search's key, null with no AI service ready; and whether it is offered at all (the pipeline
    // and settings in, *Show insights* on).
    private data class Offer(
        val key: CardKey?,
        val known: Boolean,
    )

    /** The searches shown (internal for the tests of *Clear history*'s race). */
    internal val shown = ShownSearches()

    // Every web search and AI call the questions made, this process.
    private val costs = MutableStateFlow(QuestionCost())

    /**
     * What every question made cost this process (#801): its web searches and AI calls, each counted
     * as it is made, those of questions stopped or failed included, for the tester report.
     */
    val paid: StateFlow<QuestionCost> = costs

    // The keys whose kept search was looked for, and those asked, with their calls.
    private val looked: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()
    private val asking = ConcurrentHashMap<CardKey, Job>()

    // The searches asked whose answer has settled, which *Stop* no longer cancels, and those stopped
    // before they settled, whose answer is neither shown nor kept: one or the other, under [lock].
    private val lock = Any()
    private val settled = mutableSetOf<CardKey>()
    private val stopped = mutableSetOf<CardKey>()

    // The searches stopped, by what they are about whatever the AI service, the last [STOPS_KEPT],
    // under [lock]: none is asked by itself again.
    private val declined =
        object : LinkedHashMap<Declined, Unit>(STOPS_KEPT, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Declined, Unit>?): Boolean =
                size > STOPS_KEPT
        }

    // The searches as made now, and [track]'s search of [kind]'s key (of [genre], a definition's)
    // under the settings as last read; null with no AI service ready, or before the pipeline and
    // the settings are in.
    private fun keyOf(
        track: TrackState,
        kind: SearchKind,
        genre: String?,
    ): Pair<AiSearches, CardKey>? {
        val searches = parts.value?.searches(answers, clock)
        val key = settings.value?.let { now -> searches?.key(track, kind, now, genre) }
        return if (searches == null || key == null) null else searches to key
    }

    override fun search(
        track: TrackState,
        kind: SearchKind,
        genre: String?,
    ): Flow<AiSearch> {
        val offers =
            combine(parts, settings) { made, now ->
                val searches = made?.searches(answers, clock)
                if (now == null || searches == null || !now.writeInsights) {
                    Offer(null, known = false)
                } else {
                    Offer(searches.key(track, kind, now, genre), known = true)
                }
            }
        return offers
            .distinctUntilChanged()
            .onEach { offer -> offer.key?.let { look(it, kind, track) } }
            .combine(
                combine(shown.entries, settings) { entries, now -> entries to now?.historyLifetime },
            ) { offer, (entries, lifetime) ->
                val key = offer.key
                val entry = key?.let { entries[it] }
                when {
                    !offer.known -> AiSearch.None
                    key == null -> AiSearch.Unready
                    entry == null -> AiSearch.Offered
                    expired(entry.writtenAt, lifetime) -> AiSearch.Offered.also { drop(key) }
                    else -> shownAs(entry.state, modelName(key.model))
                }
            }
    }

    override fun ask(
        track: TrackState,
        kind: SearchKind,
        facts: SearchFacts,
        automatic: Boolean,
        genre: String?,
    ): Boolean {
        // Not ready: false. Declined (by itself), shown already, unless it failed or has expired, or
        // under way: nothing to ask. A tap lifts a "no".
        val (searches, key) = keyOf(track, kind, genre) ?: return false
        val declines =
            synchronized(lock) {
                if (automatic) Declined.of(key) in declined else declined.remove(Declined.of(key)).let { false }
            }
        if (!declines && open(key) && !asking.containsKey(key)) {
            val job =
                scope.launch(
                    epoch.mark(),
                    start = CoroutineStart.LAZY,
                ) { run(searches, key, Asked(kind, genre, track, facts, automatic)) }
            if (asking.putIfAbsent(key, job) == null) {
                synchronized(lock) {
                    stopped.remove(key)
                    settled.remove(key)
                }
                job.invokeOnCompletion { asking.remove(key, job) }
                job.start()
            } else {
                // Another ask got there first (a tap and an automatic one at once): this one never runs.
                job.cancel()
            }
        }
        return true
    }

    override fun stop(
        track: TrackState,
        kind: SearchKind,
        genre: String?,
    ) {
        val key = keyOf(track, kind, genre)?.second ?: return
        val job = asking[key] ?: return
        // Once settled, the answer stands: it is shown and kept alike. Else the user's "no" to it.
        val stops =
            synchronized(lock) {
                (key !in settled && stopped.add(key)).also { if (it) declined[Declined.of(key)] = Unit }
            }
        if (stops) {
            // No longer under way at once, as the button comes back: a tap right after asks again.
            // What the cancelled job still does is neither shown nor settled (no longer active).
            job.cancel()
            asking.remove(key, job)
            shown.removeSearching(key)
        }
    }

    // Whether [key]'s search may be asked: not shown, shown as failed, or past *Keep history for*.
    private fun open(key: CardKey): Boolean {
        val entry = shown.entries.value[key]
        return entry == null || entry.state is SearchState.Failed || expired(entry.writtenAt)
    }

    // [key]'s search, kept meanwhile (another panel's), with no call, or asked; each state shown while
    // no *Clear history* came since it started and it was not stopped (nor cancelled: a job stopped
    // may outlive a tap that asks again). One [automatic] asks only if its gates still hold once the
    // cache missed, else it is handed back ([deferred]) once out of [asking].
    private suspend fun run(
        searches: AiSearches,
        key: CardKey,
        asked: Asked,
    ) {
        val kind = asked.kind
        val track = asked.track
        val work = currentCoroutineContext()
        val show: (SearchState, Instant) -> Unit = { state, at ->
            shown.publish(key, Entry(state, at)) {
                epoch.isCurrent(work) && synchronized(lock) { key !in stopped && work.isActive }
            }
        }
        try {
            val kept = searches.kept(key, kind, track)?.takeIf { !expired(it.writtenAt) }
            val gated =
                asked.automatic &&
                    !(automaticGate(track, kind, asked.genre) && keyOf(track, kind, asked.genre)?.second == key)
            if (kept != null) {
                show(kept.state, kept.writtenAt ?: clock.now())
            } else if (gated) {
                work[Job]?.let { asking.remove(key, it) }
                deferred(track, kind, asked.genre)
            } else {
                searches.search(
                    key,
                    kind,
                    track,
                    asked.facts,
                    show,
                    settle = {
                        synchronized(lock) {
                            key !in stopped &&
                                work.isActive &&
                                settled.add(key)
                        }
                    },
                    asking = AiSearches.Asking.of(settings.value, asked.automatic),
                    counted = { cost -> costs.update { it + cost } },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // A bug, not the network, which the search maps to a state: shown as failed, offered again.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            show(SearchState.Failed(e::class.simpleName ?: FAILED), clock.now())
        }
    }

    /**
     * Forgets the searches shown and the stops, and stops those under way, for *Clear history* (#547);
     * the cache's are deleted with it. After the epoch's advance: a search that checked it before
     * shows its state before this clears it, never after ([ShownSearches.publish]).
     */
    fun forget() {
        asking.values.forEach { it.cancel() }
        shown.clear()
        looked.clear()
        synchronized(lock) { declined.clear() }
    }

    // The search kept in the synthesis cache for [key], read once, unless one is shown already.
    private fun look(
        key: CardKey,
        kind: SearchKind,
        track: TrackState,
    ) {
        if (key in shown.entries.value || !looked.add(key)) return
        val searches = parts.value?.searches(answers, clock) ?: return
        scope.launch(epoch.mark()) {
            val work = coroutineContext
            val kept = searches.kept(key, kind, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                shown.publish(key, Entry(kept.state, kept.writtenAt ?: clock.now()), replace = false) {
                    epoch.isCurrent(work)
                }
            }
        }
    }

    // Whether a search written at [writtenAt] is past *Keep history for* ([lifetime]; null, for ever).
    private fun expired(
        writtenAt: Instant?,
        lifetime: Duration? = settings.value?.historyLifetime,
    ): Boolean = pastKeepHistory(writtenAt, lifetime, clock.now())

    // Drops [key]'s search, past *Keep history for*, so the cache is read again (and misses).
    private fun drop(key: CardKey) {
        shown.remove(key)
        looked.remove(key)
    }

    // A search asked: its kind, its genre (a definition's, #786), the track, what it asks about, and
    // whether it started by itself.
    private data class Asked(
        val kind: SearchKind,
        val genre: String?,
        val track: TrackState,
        val facts: SearchFacts,
        val automatic: Boolean,
    )

    // What a stop is remembered by: the track, its length and the key's subject (the kind, and for
    // *Dates* the album), whatever the AI service, model or effort. For a genre's definition (#786),
    // whose key has no track, the genre: a stop is its "no" whatever the track.
    private data class Declined(
        val track: NormalizedTrack,
        val duration: Duration?,
        val subject: String?,
    ) {
        companion object {
            fun of(key: CardKey) = Declined(key.track, key.duration, key.subject)
        }
    }

    private companion object {
        const val FAILED = "failed"

        /** The stops remembered: those of the panels of the last tracks played, a few hundred. */
        const val STOPS_KEPT = 256

        const val LOAD = .75f
    }
}

/**
 * The searches shown by [AppAiSearches] ([entries]), by key, oldest first, at most [KEPT], each with
 * when it was written. A state is shown ([publish]) only under the lock [clear] holds, after its own
 * check that it may be (no *Clear history* since its search started, not stopped): so *Clear
 * history*'s clear, which comes after the epoch's advance, either comes after a state shown, and
 * clears it, or before its check, which then fails. [beforePublish] runs between the check and the
 * showing, for the tests of that race.
 */
internal class ShownSearches {
    private val lock = Any()
    private val shown = MutableStateFlow<Map<CardKey, Entry>>(emptyMap())

    /** The searches shown, as they change. */
    val entries: StateFlow<Map<CardKey, Entry>> = shown

    /** For tests: runs between a state's check and its showing. */
    @Volatile
    var beforePublish: () -> Unit = {}

    /** Shows [entry] for [key] if [current] holds, checked under the lock; with [replace] false, not over one shown. */
    fun publish(
        key: CardKey,
        entry: Entry,
        replace: Boolean = true,
        current: () -> Boolean,
    ) = synchronized(lock) {
        if (current()) {
            beforePublish()
            shown.update { if (!replace && key in it) it else it.with(key, entry) }
        }
    }

    /** Drops [key]'s search. */
    fun remove(key: CardKey) = synchronized(lock) { shown.update { it - key } }

    /** Drops [key]'s search while it is under way: stopped, it offers the button again. */
    fun removeSearching(key: CardKey) =
        synchronized(lock) { shown.update { if (it[key]?.state is SearchState.Searching) it - key else it } }

    /** Drops every search shown (*Clear history*). */
    fun clear() = synchronized(lock) { shown.value = emptyMap() }
}

// The searches these parts make, their answers read by [answers], their times by [clock], searching
// the web with their engine (#801).
private fun PipelineParts.searches(
    answers: AiSearchAnswers,
    clock: Clock,
): AiSearches = AiSearches(ai, cache, hasher, answers, clock, webSearch)

/** A search shown, and when it was written. */
internal class Entry(
    val state: SearchState,
    val writtenAt: Instant,
)

// [state] as the panel shows it, by the model named [model].
private fun shownAs(
    state: SearchState,
    model: String,
): AiSearch =
    when (state) {
        is SearchState.Searching -> AiSearch.Searching(state.web)
        is SearchState.Answered -> AiSearch.Answered(state.answer, model, state.way)
        is SearchState.NothingFound -> AiSearch.NothingFound(model, state.way)
        is SearchState.Failed -> AiSearch.Failed(state.reason)
    }

// [this] with [entry] for [key], last, the oldest dropped past [KEPT].
private fun Map<CardKey, Entry>.with(
    key: CardKey,
    entry: Entry,
): Map<CardKey, Entry> {
    val next = LinkedHashMap(this)
    next.remove(key)
    next[key] = entry
    while (next.size > KEPT) next.remove(next.keys.first())
    return next
}

/** Enough for the panels of the last tracks played; the cache keeps the rest. */
private const val KEPT = 64
