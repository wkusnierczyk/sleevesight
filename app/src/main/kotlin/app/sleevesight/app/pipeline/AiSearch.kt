package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.SearchState
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
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** *Search with AI* under an empty panel on Home (#773), as the panel shows it. */
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

    /** Under way: the turning record and "Searching with AI…", and *Stop*. */
    data object Searching : AiSearch

    /** Answered by [model], as checked. */
    data class Answered(
        val answer: SearchAnswer,
        val model: String,
    ) : AiSearch

    /** Answered by [model] with nothing usable: "{model} found nothing.". */
    data class NothingFound(
        val model: String,
    ) : AiSearch

    /** The call failed, for [reason]; offered again. */
    data class Failed(
        val reason: String,
    ) : AiSearch
}

/**
 * *Search with AI* under the empty state of Home's *Dates*, *Genres* or *Lyrics* (#773): each
 * panel's search, by its [SearchKind], for a track.
 */
interface AiSearchFeed {
    /** [track]'s search of [kind], as it changes. */
    fun search(
        track: TrackState,
        kind: SearchKind,
    ): Flow<AiSearch>

    /**
     * A tap on *Search with AI* or *Retry*, or the search starting by itself (*Search with AI*, the
     * setting, [AutoSearches]): [track]'s search of [kind] asks about [facts], unless it is under way,
     * answered or kept. Returns whether the search is now asked, under way, answered or kept: false
     * with no AI service ready, or with *Show insights* off.
     */
    fun ask(
        track: TrackState,
        kind: SearchKind,
        facts: SearchFacts,
    ): Boolean

    /** A tap on *Stop*: [track]'s search of [kind] is cancelled, and nothing from it shown or kept. */
    fun stop(
        track: TrackState,
        kind: SearchKind,
    )

    /** No search: the panels show their empty states alone (previews, tests). */
    object None : AiSearchFeed {
        override fun search(
            track: TrackState,
            kind: SearchKind,
        ): Flow<AiSearch> = flowOf(AiSearch.None)

        override fun ask(
            track: TrackState,
            kind: SearchKind,
            facts: SearchFacts,
        ) = false

        override fun stop(
            track: TrackState,
            kind: SearchKind,
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
 * and effort, the prompts' version), so it shows again with no call when the panel is closed and
 * opened or the page left; and in the synthesis cache, read the first time a panel asks for it
 * ([search]), and again before any call ([ask]), so it shows again when the track plays later, and a
 * search that starts by itself never pays for an answer kept. Each is kept with when it was written
 * (by [clock], or as the cache says), and shown no longer than *Keep history for* after it, as the
 * cache serves it; past it, it is dropped and offered again. A failure is shown, not kept: offered
 * again beside it.
 *
 * The call runs in [scope], the process's, so leaving the panel or the page does not stop a call
 * paid for; *Stop* ([stop]) does, and nothing from it is shown or kept: a stop that comes once the
 * answer has settled leaves it. Each search is marked with [epoch], and after *Clear history*
 * ([forget]) nothing it finds is shown or kept.
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
) : AiSearchFeed {
    // A search's key, null with no AI service ready; and whether it is offered at all (the pipeline
    // and settings in, *Show insights* on).
    private data class Offer(
        val key: CardKey?,
        val known: Boolean,
    )

    // The searches shown, by key, oldest first, at most [KEPT].
    private val shown = MutableStateFlow<Map<CardKey, Entry>>(emptyMap())

    // The keys whose kept search was looked for, and those asked, with their calls.
    private val looked: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()
    private val asking = ConcurrentHashMap<CardKey, Job>()

    // The searches asked whose answer has settled, which *Stop* no longer cancels, and those stopped
    // before they settled, whose answer is neither shown nor kept: one or the other, under [lock].
    private val lock = Any()
    private val settled = mutableSetOf<CardKey>()
    private val stopped = mutableSetOf<CardKey>()

    // The searches as made now, and [track]'s search of [kind]'s key under the settings as last
    // read; null with no AI service ready, or before the pipeline and the settings are in.
    private fun keyOf(
        track: TrackState,
        kind: SearchKind,
    ): Pair<AiSearches, CardKey>? {
        val searches = parts.value?.searches(answers, clock)
        val key = settings.value?.let { now -> searches?.key(track, kind, now) }
        return if (searches == null || key == null) null else searches to key
    }

    override fun search(
        track: TrackState,
        kind: SearchKind,
    ): Flow<AiSearch> {
        val offers =
            combine(parts, settings) { made, now ->
                val searches = made?.searches(answers, clock)
                if (now == null || searches == null || !now.writeInsights) {
                    Offer(null, known = false)
                } else {
                    Offer(searches.key(track, kind, now), known = true)
                }
            }
        return offers
            .distinctUntilChanged()
            .onEach { offer -> offer.key?.let { look(it, kind, track) } }
            .combine(
                combine(shown, settings) { entries, now -> entries to now?.historyLifetime },
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
    ): Boolean {
        // Not ready: false. Shown already, unless it failed or has expired, or under way: nothing to ask.
        val (searches, key) = keyOf(track, kind) ?: return false
        if (open(key) && !asking.containsKey(key)) {
            val job = scope.launch(epoch.mark(), start = CoroutineStart.LAZY) { run(searches, key, kind, track, facts) }
            if (asking.putIfAbsent(key, job) == null) {
                synchronized(lock) {
                    stopped.remove(key)
                    settled.remove(key)
                }
                job.invokeOnCompletion { asking.remove(key, job) }
                job.start()
            }
        }
        return true
    }

    override fun stop(
        track: TrackState,
        kind: SearchKind,
    ) {
        val key = keyOf(track, kind)?.second ?: return
        val job = asking[key] ?: return
        // Once settled, the answer stands: it is shown and kept alike.
        if (synchronized(lock) { key !in settled && stopped.add(key) }) {
            job.cancel()
            shown.update { if (it[key]?.state == SearchState.Searching) it - key else it }
        }
    }

    // Whether [key]'s search may be asked: not shown, shown as failed, or past *Keep history for*.
    private fun open(key: CardKey): Boolean {
        val entry = shown.value[key]
        return entry == null || entry.state is SearchState.Failed || expired(entry.writtenAt)
    }

    // [key]'s search, kept meanwhile (another panel's), with no call, or asked; each state shown while
    // no *Clear history* came since it started and it was not stopped.
    @Suppress("LongParameterList") // The search, its key, kind and track, and what it asks about.
    private suspend fun run(
        searches: AiSearches,
        key: CardKey,
        kind: SearchKind,
        track: TrackState,
        facts: SearchFacts,
    ) {
        val work = currentCoroutineContext()
        val show: (SearchState, Instant) -> Unit = { state, at ->
            val stops = synchronized(lock) { key in stopped }
            if (epoch.isCurrent(work) && !stops) shown.update { it.with(key, Entry(state, at)) }
        }
        try {
            val kept = searches.kept(key, kind, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                show(kept.state, kept.writtenAt ?: clock.now())
            } else {
                searches.search(key, kind, track, facts, show) {
                    synchronized(lock) {
                        key !in stopped &&
                            settled.add(key)
                    }
                }
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
     * Forgets the searches shown and stops those under way, for *Clear history* (#547); the cache's
     * are deleted with it.
     */
    fun forget() {
        asking.values.forEach { it.cancel() }
        shown.value = emptyMap()
        looked.clear()
    }

    // The search kept in the synthesis cache for [key], read once, unless one is shown already.
    private fun look(
        key: CardKey,
        kind: SearchKind,
        track: TrackState,
    ) {
        if (key in shown.value || !looked.add(key)) return
        val searches = parts.value?.searches(answers, clock) ?: return
        scope.launch(epoch.mark()) {
            val work = coroutineContext
            val kept = searches.kept(key, kind, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null && epoch.isCurrent(work)) {
                val entry = Entry(kept.state, kept.writtenAt ?: clock.now())
                shown.update { if (key in it) it else it.with(key, entry) }
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
        shown.update { it - key }
        looked.remove(key)
    }

    private companion object {
        const val FAILED = "failed"
    }
}

// The searches these parts make, their answers read by [answers], their times by [clock].
private fun PipelineParts.searches(
    answers: AiSearchAnswers,
    clock: Clock,
): AiSearches = AiSearches(ai, cache, hasher, answers, clock)

// A search shown, and when it was written.
private class Entry(
    val state: SearchState,
    val writtenAt: Instant,
)

// [state] as the panel shows it, by the model named [model].
private fun shownAs(
    state: SearchState,
    model: String,
): AiSearch =
    when (state) {
        SearchState.Searching -> AiSearch.Searching
        is SearchState.Answered -> AiSearch.Answered(state.answer, model)
        SearchState.NothingFound -> AiSearch.NothingFound(model)
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
