package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.AiOutcome
import app.sleevesight.core.pipeline.AiQuestion
import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.ItemInsights
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.settings.pastKeepHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What the song's, the artist's or the album's row shows unfolded, or its page (#522). */
sealed interface ItemShown {
    /** The track's evidence, or the album's, not in yet. */
    data object Gathering : ItemShown

    /** *Show insights* off: nothing is written. */
    data object Off : ItemShown

    /** No AI service ready (none switched on, no key, or no model). */
    data object NoService : ItemShown

    /** Nothing written, and nothing asked: *Write insight* offered. */
    data object Offered : ItemShown

    /**
     * Asked for, its call not started yet: its evidence or the cache being read, then the gates; it
     * goes back to [Offered] when they refuse the call. [key] is its card's, which *Stop writing* stops.
     */
    data class Asking(
        val key: CardKey,
    ) : ItemShown

    /**
     * Nothing to write about on this track: no name for it (no album the player names, a blank
     * title), so neither its evidence nor a call.
     */
    data object NoSubject : ItemShown

    /**
     * Asked for, or kept from before: [state] is [InsightState.Writing], [InsightState.Written],
     * [InsightState.NoEvidence] (the sources give nothing to write it from, with no call, or the
     * model found nothing), [InsightState.Failed] or [InsightState.Stopped], beside which it is
     * offered again, on a tap only. [model] is
     * the name of the model that writes it, for "by {model}"; [evidence] what it was written from, which
     * its citations read, each mapped to the track's line where it is one ([ItemEvidence.trackIds]).
     */
    data class Shown(
        val state: InsightState,
        val evidence: ItemEvidence,
        val model: String? = null,
        val key: CardKey? = null,
    ) : ItemShown
}

/** How an item's insight came to be asked for (#522), which says what may start its call. */
enum class ItemAsk {
    /** A tap on *Write insight*, or on *Retry* after a failure: written again after a failure or a Stop. */
    TAP,

    /** Its row unfolded, or its page opened, by a tap, under an automatic mode. */
    OPENED,

    /** Its row unfolded at a new track's start, under an automatic mode: as the track's own writing is. */
    BY_ITSELF,
}

/** The song's, the artist's and the album's insights under *Key insights* (#522), as Home and their pages show them. */
interface ItemNotes {
    /** [item]'s insight on [track], as it changes. */
    fun item(
        track: TrackState,
        item: InsightItem,
    ): Flow<ItemShown>

    /**
     * [item]'s insight on [track] asked for, as [ask] says: written, a paid call, unless it is shown,
     * kept (no call), or under way; one that failed or was stopped only on a tap ([ItemAsk.TAP]).
     */
    fun write(
        track: TrackState,
        item: InsightItem,
        ask: ItemAsk = ItemAsk.TAP,
    )

    /**
     * The album's own lines (#522) gathered for [track]'s album's insight on this track, as they come;
     * null until it is gathered (unfolded or opened): what *Sources* lists beside the track's.
     */
    fun albumEvidence(track: TrackState): Flow<EvidenceSet?> = flowOf(null)

    /**
     * *Stop writing* under the insight of card [key] (as [ItemShown.Asking] or [ItemShown.Shown] carry
     * it): the write under way is cut off, nothing is kept, and it shows as stopped.
     */
    fun stop(key: CardKey) = Unit

    /** None: the rows say nothing is written (previews, tests). */
    object None : ItemNotes {
        override fun item(
            track: TrackState,
            item: InsightItem,
        ): Flow<ItemShown> = flowOf(ItemShown.NoService)

        override fun write(
            track: TrackState,
            item: InsightItem,
            ask: ItemAsk,
        ) = Unit
    }
}

/**
 * The app's [ItemNotes]: each insight written with the live pipeline's [parts] (its AI service,
 * synthesis cache, hasher and album lookup, [ItemInsights]) under the [settings] as last read, its
 * names read by [names], the models named as the AI settings name them ([modelName]).
 *
 * The evidence for each item of a track is gathered once (the album's off the main thread, [io]),
 * and kept in memory by the track's evidence and the item's entity. An insight is kept in memory by
 * its card's key, so it shows again with no call when its row is folded and unfolded, or its page
 * left and opened again; and in the synthesis cache, read the first time its row asks, so it shows
 * again, with no call, when the track plays again later, and on every track of its artist or album
 * for the artist's and the album's (#240). A settings change that changes its key (another AI
 * service, model or effort, new evidence) offers it again. Each is shown no longer than *Keep
 * history for* after it was written ([clock]), as the cache serves it. The writing runs in [scope],
 * the process's, so leaving Home does not stop a call paid for; it is marked with [epoch], and after
 * *Clear history* ([forget]) nothing it writes is shown or kept. Each call is counted to [paid], and
 * told to [events] with what was served from the cache, as a genre's reason is (#851).
 *
 * A call starts only through the coordinator's gates ([gate], `PipelineHost.itemCallAllowed`: the
 * app on screen or *Look up in the background* on, and for one asked by itself what holds the
 * track's own automatic writing back), asked right before it, after any wait for the evidence or
 * the cache, with the settings read again then (insights on, the same AI service, model and effort,
 * an automatic mode for one not tapped); closed, it is not made, and the item stays offered. *Stop
 * writing* ([stop]) cancels it: nothing is kept, and it shows as stopped, written again only on a tap.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Where items come from, where they run, and the time; each step.
class AppItemNotes(
    private val parts: StateFlow<PipelineParts?>,
    private val settings: StateFlow<Settings?>,
    private val scope: CoroutineScope,
    private val names: NameMatcher,
    private val epoch: ClearEpoch = ClearEpoch(),
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val clock: Clock = Clock.System,
    private val modelName: (String) -> String = { ModelNames.of(it) ?: it },
    private val paid: (QuestionCost) -> Unit = {},
    private val events: (PipelineEvent) -> Unit = {},
    private val gate: (ItemAsk) -> Boolean = { true },
) : ItemNotes {
    // An insight shown, and when it was written.
    private class Entry(
        val state: InsightState,
        val writtenAt: Instant,
        val evidence: ItemEvidence,
    )

    // What an item's evidence is gathered for: the item, the run's own evidence under the switches
    // now (`ItemInsights.ownEvidence`), its entity, and the sources switched on.
    private data class Gathered(
        val item: InsightItem,
        val lookupKey: String,
        val fingerprint: String,
        val entity: CardEntity?,
        val sources: Set<SourceId>,
    )

    // The insights shown, by key, oldest first, at most [KEPT].
    private val shown = MutableStateFlow<Map<CardKey, Entry>>(emptyMap())

    // Evidence gathered, and when.
    private class Found(
        val evidence: ItemEvidence,
        val at: Instant,
    )

    // The evidence gathered, by what it was gathered for, oldest first, at most [KEPT]: one a source
    // could not be asked for too, for [UNREACHABLE_FOR], so it is not asked again at every update.
    private val gathered = MutableStateFlow<Map<Gathered, Found>>(emptyMap())

    // The gathering under way, by what it is for: one at a time, shared by every row, page and write.
    private val inflight = ConcurrentHashMap<Gathered, Deferred<Found?>>()

    // The calls under way, by key, for *Stop writing*.
    private val jobs = ConcurrentHashMap<CardKey, Job>()

    // The keys whose cached insight was looked for, and those being written.
    private val looked: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()
    private val writing = MutableStateFlow<Set<CardKey>>(emptySet())

    // The work under way (gathering, reading the cache, writing), cancelled by *Clear history*.
    private val pending: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun item(
        track: TrackState,
        item: InsightItem,
    ): Flow<ItemShown> =
        combine(parts, settings) { made, now -> made to now }
            .distinctUntilChanged()
            .flatMapLatest { (made, now) ->
                val insights = made?.let(::insightsOf)
                when {
                    insights == null || now == null -> flowOf(ItemShown.Gathering)
                    !now.writeInsights -> flowOf(ItemShown.Off)
                    insights.name(item, track) == null -> flowOf(ItemShown.NoSubject)
                    !ItemInsights.offered(track) -> flowOf(ItemShown.Gathering)
                    else -> shownFlow(insights, track, item, now)
                }
            }.distinctUntilChanged()

    // [item]'s insight on [track] under [now], once its evidence is gathered: the gathering started,
    // or joined, and its result followed as it is kept.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun shownFlow(
        insights: ItemInsights,
        track: TrackState,
        item: InsightItem,
        now: Settings,
    ): Flow<ItemShown> {
        val at = gatheredFor(insights, track, item, now)
        if (!usable(gathered.value[at])) gather(insights, track, item, now, at)
        return gathered
            .map { it[at]?.evidence }
            .distinctUntilChanged()
            .flatMapLatest { evidence ->
                val key = evidence?.let { insights.key(item, track, it, now) }
                when {
                    evidence == null -> flowOf(ItemShown.Gathering)
                    key == null -> flowOf(ItemShown.NoService)
                    else -> {
                        look(insights, key, track, item, evidence)
                        combine(shown, writing, settings) { entries, asked, latest ->
                            shownFor(key, evidence, entries[key], latest?.historyLifetime, key in asked)
                        }
                    }
                }
            }
    }

    private fun shownFor(
        key: CardKey,
        evidence: ItemEvidence,
        entry: Entry?,
        lifetime: Duration?,
        asked: Boolean,
    ): ItemShown =
        when {
            entry == null && asked -> ItemShown.Asking(key)
            entry == null -> ItemShown.Offered
            expired(entry.writtenAt, lifetime) -> ItemShown.Offered.also { drop(key) }
            // The insight's evidence numbered as when it was written, but mapped to this track's lines:
            // an artist's or album's insight kept from another track cites the same ids (#522).
            else -> ItemShown.Shown(entry.state, evidence, modelName(key.model), key)
        }

    override fun write(
        track: TrackState,
        item: InsightItem,
        ask: ItemAsk,
    ) {
        val made = parts.value
        val now = settings.value?.takeIf { it.writeInsights && ItemInsights.offered(track) }
        if (made == null || now == null) return
        tracked {
            val work = coroutineContext
            val insights = insightsOf(made)
            val evidence = evidenceOf(insights, track, item, now)
            // Not after a *Clear history* that came while the evidence was gathered.
            val key = evidence?.let { insights.key(item, track, it, now) }?.takeIf { current(work) }
            if (evidence != null && key != null && claim(key, ask)) {
                val job = coroutineContext[Job]
                job?.let { jobs[key] = it }
                try {
                    write(insights, key, Asked(item, track, evidence, ask, work))
                } finally {
                    jobs.remove(key, job)
                }
            }
        }
    }

    // [block] in [scope], marked with the epoch and the count of *Clear history*, held in [pending]
    // until it ends, so a clear cancels it.
    private fun tracked(block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(mark(), start = CoroutineStart.LAZY, block = block)
        pending += job
        job.invokeOnCompletion { pending -= job }
        job.start()
    }

    // The album's lines gathered for [track] and its album as it names it now (its entity: the
    // player's album name, its release group): the same song from another album has none of them.
    override fun albumEvidence(track: TrackState): Flow<EvidenceSet?> =
        combine(gathered, parts) { kept, made ->
            val album = made?.let { insightsOf(it).entity(InsightItem.ALBUM, track) }
            kept.entries
                .lastOrNull { (at, _) ->
                    album != null &&
                        at.item == InsightItem.ALBUM &&
                        at.lookupKey == track.normalized.lookupKey &&
                        at.entity == album
                }?.value
                ?.evidence
                ?.evidence
        }.distinctUntilChanged()

    // The write under way for [key], by its own job, whatever the evidence kept meanwhile.
    override fun stop(key: CardKey) {
        jobs[key]?.cancel(StopAsked())
    }

    // Whether [ask]'s call may start now (#522): the coordinator's [gate] open for it, and under the
    // settings now (after any wait for the evidence or the cache), insights on, the same AI service,
    // model and effort as [key] was made with, and, for one not tapped, an automatic mode still.
    private fun mayCall(
        key: CardKey,
        asked: Asked,
    ): Boolean {
        val now = settings.value
        val made = parts.value
        val same =
            now != null && made != null && insightsOf(made).key(asked.item, asked.track, asked.evidence, now) == key
        val mode = asked.ask == ItemAsk.TAP || now?.writeAutomatically != WriteAutomatically.NEVER
        // The service selected now, the one the parts carry and the card's (`ItemInsights.key` asks
        // the first two): a switch waits for the rebuild that brings the new service.
        val service = now?.aiProvider == key.provider && made?.ai?.provider?.id == key.provider
        return same && mode && service && current(asked.work) && gate(asked.ask)
    }

    // An item asked for: what it is, of which track, from what evidence, and how it was asked.
    private class Asked(
        val item: InsightItem,
        val track: TrackState,
        val evidence: ItemEvidence,
        val ask: ItemAsk,
        val work: CoroutineContext,
    )

    // What a Stop cancels an item's call with, told apart from the scope's own cancellation.
    private class StopAsked : CancellationException("stopped")

    // The evidence for [item] on [track] under [now]: kept, or the gathering under way joined, or
    // started; the kept one while that gives none.
    private suspend fun evidenceOf(
        insights: ItemInsights,
        track: TrackState,
        item: InsightItem,
        now: Settings,
    ): ItemEvidence? {
        val at = gatheredFor(insights, track, item, now)
        val known = gathered.value[at]
        return if (usable(known)) {
            known?.evidence
        } else {
            gather(insights, track, item, now, at).await()?.evidence
                ?: known?.evidence
        }
    }

    // Whether [found] is shown and written from as it is: one a source could not be asked for only
    // for [UNREACHABLE_FOR], then gathered again.
    private fun usable(found: Found?): Boolean =
        found != null && (!found.evidence.unreachable || clock.now() - found.at < UNREACHABLE_FOR)

    // The gathering of [at]'s evidence (off the main thread): the one under way, or a new one in
    // [scope], which outlives whoever asked; kept once it ends, unless a *Clear history* came since it
    // began, which also cancels it.
    private fun gather(
        insights: ItemInsights,
        track: TrackState,
        item: InsightItem,
        now: Settings,
        at: Gathered,
    ): Deferred<Found?> {
        val gathering =
            inflight.computeIfAbsent(at) {
                val job =
                    scope.async(mark(), start = CoroutineStart.LAZY) {
                        val found =
                            withContext(
                                io,
                            ) { insights.evidence(item, track, now) }?.let { Found(it, clock.now()) }
                        found?.also { keep(at, it, coroutineContext) }
                    }
                pending += job
                job.invokeOnCompletion {
                    pending -= job
                    inflight.remove(at, job)
                }
                job
            }
        // Started once it is in [inflight], so its end cannot remove it before it is there.
        gathering.start()
        return gathering
    }

    // Keeps [found] for [at], last, the oldest dropped past [KEPT], unless a *Clear history* came since
    // [work] began.
    private fun keep(
        at: Gathered,
        found: Found,
        work: CoroutineContext,
    ) = publish(work) {
        gathered.update { kept ->
            val next = LinkedHashMap(kept)
            next.remove(at)
            next[at] = found
            while (next.size > KEPT) next.remove(next.keys.first())
            next
        }
    }

    private fun gatheredFor(
        insights: ItemInsights,
        track: TrackState,
        item: InsightItem,
        now: Settings,
    ) = Gathered(
        item,
        track.normalized.lookupKey,
        insights.ownEvidence(track, now)?.fingerprint.orEmpty(),
        insights.entity(item, track),
        SourceId.entries.filter(now::isEnabled).toSet(),
    )

    // [key]'s insight, kept meanwhile (another track of the artist or album wrote it), with no call,
    // or written; each state shown while no *Clear history* came since it started.
    private suspend fun write(
        insights: ItemInsights,
        key: CardKey,
        asked: Asked,
    ) {
        val item = asked.item
        val track = asked.track
        val evidence = asked.evidence
        val work = currentCoroutineContext()
        val show: (InsightState, Instant) -> Unit = { state, at ->
            publish(work) { shown.update { it.with(key, Entry(state, at, evidence)) } }
        }
        val question = AiQuestion.Item(item)
        var started: Instant? = null
        var cost = QuestionCost()

        fun end(
            outcome: AiOutcome,
            failure: String? = null,
        ) {
            val at = started ?: return
            started = null
            note(
                work,
                PipelineEvent.AiEnded(track.normalized.lookupKey, question, outcome, cost, clock.now() - at, failure),
            )
        }
        try {
            val kept =
                insights
                    .kept(
                        key,
                        evidence,
                    )?.takeIf { !expired(it.writtenAt) && it.state !is InsightState.Failed }
            if (kept != null) {
                note(work, kept(track, key, question, kept.writtenAt))
                show(kept.state, kept.writtenAt ?: clock.now())
                return
            }
            note(work, PipelineEvent.AiAsked(track.normalized.lookupKey, question, false, key.provider, key.model))
            started = clock.now()
            val counted: (QuestionCost) -> Unit = { more ->
                cost += more
                paid(more)
            }
            val last = insights.write(key, item, track, evidence, { mayCall(key, asked) }, counted, show)
            if (last == null) {
                // Its gates closed while the evidence or the cache was read: not asked, offered again.
                started = null
                note(work, PipelineEvent.AiNotAsked(track.normalized.lookupKey, question))
                return
            }
            end(outcomeOf(last), (last as? InsightState.Failed)?.reason)
        } catch (e: StopAsked) {
            // Stopped by the user (*Stop writing*): said so, offered again on a tap; nothing was kept.
            end(AiOutcome.STOPPED)
            show(InsightState.Stopped, clock.now())
            throw e
        } catch (e: CancellationException) {
            end(AiOutcome.STOPPED)
            throw e
        } catch (
            // A bug, not the network, which the writer maps to a state: shown as failed, offered again.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val reason = e::class.simpleName ?: FAILED
            end(AiOutcome.FAILED, reason)
            show(InsightState.Failed(reason), clock.now())
        } finally {
            writing.update { it - key }
        }
    }

    // How an item's question ended, as the diagnostics name it, from its last state.
    private fun outcomeOf(last: InsightState): AiOutcome =
        when (last) {
            is InsightState.Written -> AiOutcome.ANSWERED
            InsightState.NoEvidence -> AiOutcome.NOTHING_FOUND
            is InsightState.Failed -> AiOutcome.FAILED
            else -> AiOutcome.STOPPED
        }

    // [key]'s insight kept in the synthesis cache, read once, unless one is shown already.
    private fun look(
        insights: ItemInsights,
        key: CardKey,
        track: TrackState,
        item: InsightItem,
        evidence: ItemEvidence,
    ) {
        if (key in shown.value || !looked.add(key)) return
        tracked {
            val work = coroutineContext
            val kept = insights.kept(key, evidence)?.takeIf { !expired(it.writtenAt) } ?: return@tracked
            val entry = Entry(kept.state, kept.writtenAt ?: clock.now(), evidence)
            publish(work) {
                var added = false
                shown.update { (if (key in it) it else it.with(key, entry)).also { next -> added = next !== it } }
                if (added) events(kept(track, key, AiQuestion.Item(item), kept.writtenAt))
            }
        }
    }

    // [key]'s answer to [question], kept, written at [writtenAt], served for [track] (#851).
    private fun kept(
        track: TrackState,
        key: CardKey,
        question: AiQuestion,
        writtenAt: Instant?,
    ) = PipelineEvent.AiKept(
        track.normalized.lookupKey,
        question,
        key.provider,
        key.model,
        writtenAt,
        automatic = false,
    )

    // Whether [key]'s insight may be asked for ([open]), and if so, marked as being written, unless it is already.
    private fun claim(
        key: CardKey,
        ask: ItemAsk,
    ): Boolean {
        var added = false
        if (open(key, ask)) {
            writing.update { asked ->
                added = key !in asked
                asked + key
            }
        }
        return added
    }

    // Whether [key]'s insight may be asked for: not shown, or past *Keep history for*; on a tap, also
    // one that failed or was stopped, which nothing asks again by itself.
    private fun open(
        key: CardKey,
        ask: ItemAsk,
    ): Boolean {
        val entry = shown.value[key]
        val again = ask == ItemAsk.TAP && (entry?.state is InsightState.Failed || entry?.state == InsightState.Stopped)
        return entry == null || again || expired(entry.writtenAt)
    }

    /**
     * Forgets the insights shown and the evidence gathered, for *Clear history* (#547); the cache's
     * are deleted with it. After the epoch's advance: work that checked it before shows its state
     * before this clears it, never after.
     */
    fun forget() {
        synchronized(lock) {
            forgets++
            shown.value = emptyMap()
            gathered.value = emptyMap()
            inflight.clear()
            looked.clear()
        }
        // What was gathering, reading or writing goes: nothing it found is kept or shown, and no call
        // it would have made is made.
        pending.toList().forEach { it.cancel() }
    }

    // Guards each insight shown against *Clear history* ([forget]).
    private val lock = Any()

    // How many times [forget] ran, under [lock], as `AppGenreNotes` counts them (#791).
    @Volatile
    private var forgets = 0L

    // The count of [forget]s a piece of work started after; its children inherit it.
    private class Forgets(
        val count: Long,
    ) : AbstractCoroutineContextElement(Forgets) {
        companion object Key : CoroutineContext.Key<Forgets>
    }

    private fun mark(): CoroutineContext = epoch.mark() + Forgets(forgets)

    private fun current(work: CoroutineContext): Boolean =
        epoch.isCurrent(work) && work[Forgets]?.count.let { it == null || it == forgets }

    // Runs [show] if no *Clear history* came since [work] started, under the lock [forget] clears under.
    private fun publish(
        work: CoroutineContext,
        show: () -> Unit,
    ) = synchronized(lock) { if (current(work)) show() }

    // Tells [event] to the log if no *Clear history* came since [work] started.
    private fun note(
        work: CoroutineContext,
        event: PipelineEvent,
    ) = synchronized(lock) { if (current(work)) events(event) }

    private fun insightsOf(made: PipelineParts) =
        ItemInsights(made.ai, made.cache, made.hasher, names, clock, made.albums)

    // Whether an insight written at [writtenAt] is past *Keep history for* ([lifetime]; null, for ever).
    private fun expired(
        writtenAt: Instant?,
        lifetime: Duration? = settings.value?.historyLifetime,
    ): Boolean = pastKeepHistory(writtenAt, lifetime, clock.now())

    // Drops [key]'s insight, past *Keep history for*, so the cache is read again (and misses).
    private fun drop(key: CardKey) {
        shown.update { it - key }
        looked.remove(key)
    }

    // [this] with [entry] for [key], last, the oldest dropped past [KEPT], and no longer counted as looked for.
    private fun Map<CardKey, Entry>.with(
        key: CardKey,
        entry: Entry,
    ): Map<CardKey, Entry> {
        val next = LinkedHashMap(this)
        next.remove(key)
        next[key] = entry
        while (next.size > KEPT) {
            val oldest = next.keys.first()
            next.remove(oldest)
            looked.remove(oldest)
        }
        return next
    }

    private companion object {
        /** Enough for the items of the last tracks played; the cache keeps the rest. */
        const val KEPT = 64

        const val FAILED = "failed"

        /** How long evidence a source could not be asked for is kept before it is gathered again. */
        val UNREACHABLE_FOR: Duration = 1.minutes
    }
}
