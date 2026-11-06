package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.AiOutcome
import app.sleevesight.core.pipeline.AiQuestion
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.ItemInsights
import app.sleevesight.core.pipeline.ItemSearch
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.WriteGates
import app.sleevesight.core.pipeline.WrittenBy
import app.sleevesight.core.pipeline.rewriteParts
import app.sleevesight.core.pipeline.writtenBy
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
import kotlinx.coroutines.flow.onEach
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
     *
     * [rewriteWith] (#911) when [state] is the insight another AI service, model or effort wrote, kept,
     * shown in place of none under [key]: *Rewrite insight* is offered beside it, and this is what a
     * rewrite would use that it did not, and what it would write with; [model] is then the one that
     * wrote it. [rewriteFailed] when a rewrite of it ended without an insight: stopped
     * ([PipelineHost.STOPPED]), or failed, and why; it is shown again, and *Rewrite insight* offered
     * again. Both null for the item's own insight. [canWrite] is false while no insight may be written
     * (no AI service ready: the model found unavailable, #540): what is kept still shows, and *Rewrite
     * insight* is not offered.
     */
    data class Shown(
        val state: InsightState,
        val evidence: ItemEvidence,
        val model: String? = null,
        val key: CardKey? = null,
        val rewriteWith: RewriteOffer? = null,
        val rewriteFailed: String? = null,
        val canWrite: Boolean = true,
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
     * The artist's lines gathered for [track]'s artist's insight on this track, as they come, the web's
     * lines for an artist the sources say nothing about among them (#912); null until it is gathered:
     * what *Sources* lists beside the track's, its lines the track has already once.
     */
    fun artistEvidence(track: TrackState): Flow<EvidenceSet?> = flowOf(null)

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

// What a web search on the way to an item's write gave (`AppItemNotes.searched`, #885, #912): the
// evidence with its lines, and the key the insight then has; or a failure, the artist's, which keeps
// nothing.
private sealed interface WebStep {
    class Found(
        val key: CardKey,
        val evidence: ItemEvidence,
    ) : WebStep

    data object Failed : WebStep

    data object Refused : WebStep
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
 *
 * With none kept under its card's key, the insight another AI service, model or effort wrote for the
 * same item and evidence, kept in memory or in the cache, the most recently written, is shown in its
 * place, with *Rewrite insight* (#911): read only once the key's own is not found, so it never stands
 * in for it. A tap on *Rewrite insight* writes it with the settings now, a rewrite, which its footer
 * says; one stopped or failed shows the earlier insight again, saying so, with *Rewrite insight*. Under
 * an automatic mode the item is written anew when opened, as for any item its model has not written,
 * but not on the play its row showed an insight before the model changed ([held]): that one stays,
 * with *Rewrite insight*, as the key insight's does after a settings change.
 *
 * An album's web search (#885, `ItemInsights.searched`), a paid one, is made only on the way to its
 * insight's call: once the insight is not found kept under the key of the evidence gathered,
 * through the same gates, with web search still switched on, its engine and key as the settings
 * and parts have them right before it, never when a row is shown or unfolded; the gates are asked
 * again after the lookup's waits, right before the request (#935): closed, nothing is sent, and the
 * album is offered again. Each request sent to the engine is counted to [paid], lines kept from an
 * earlier search not. Its lines join the album's evidence gathered, which the album lookup keeps, so
 * the insight then has another key, under which the cache is read again before the call, and which
 * a later gathering finds again; that key is claimed before the lines are kept, so another track
 * gathering them meanwhile finds it being written, rather than paying for a write of its own.
 * Whether the album may be searched (web search on, an engine's key) is part of what its evidence is
 * gathered for, so giving the key gathers it again, a search due.
 *
 * The artist's web search (#912), for an artist the sources say nothing about, is made the same way,
 * with *AI web search* used: under *Once*, the app's own search before the call, as the album's; one
 * that fails keeps nothing and shows the write failed ([AiSearches.WEB_SEARCH_FAILED]), *Retry*
 * offered. Under *Up to 3 times*, the AI service searches itself while it writes
 * (`ItemInsights.writeSearching`), through the same gates, asked right before it, each search and
 * call counted to [paid]; the row shows the insight asked for until the reply comes whole, then
 * under the key the pages found give it, which the artist's evidence gathered then holds.
 */
@Suppress(
    "LongParameterList", // Where items come from, where they run, and the time.
    "TooManyFunctions", // Each step.
    "LargeClass", // Each step of an item's write, its web search on the way among them (#885, #912).
)
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
    // An insight shown, and when it was written; [rewrites] when the write that showed it, a tap's or
    // an automatic one's (#972), rewrites the insight another service, model or effort wrote (#911),
    // and [play] the play ([NowPlaying.play]) it was written on, null where none was counted or it
    // came from the cache.
    private class Entry(
        val state: InsightState,
        val writtenAt: Instant,
        val evidence: ItemEvidence,
        val rewrites: Boolean = false,
        val play: Long? = null,
    )

    // What an item's evidence is gathered for: the item, the run's own evidence under the switches
    // now (`ItemInsights.ownEvidence`), its entity, the sources switched on, and whether a web search
    // may give it lines (`ItemInsights.searchesWeb`: the artist's, #912, with *AI web search* used and
    // an engine's key; the album's, #935, with web search on and an engine's key), so switching that
    // on, or giving the key, gathers it again.
    private data class Gathered(
        val item: InsightItem,
        val lookupKey: String,
        val fingerprint: String,
        val entity: CardEntity?,
        val sources: Set<SourceId>,
        val searchesWeb: Boolean,
    )

    // The insights shown, by key, oldest first, at most [KEPT].
    private val shown = MutableStateFlow<Map<CardKey, Entry>>(emptyMap())

    // Evidence gathered, and when; on which track, under which settings (for an album's web lines
    // found on another of its tracks, [spread]).
    private class Found(
        val evidence: ItemEvidence,
        val at: Instant,
        val track: TrackState,
        val settings: Settings,
    )

    // The evidence gathered, by what it was gathered for, oldest first, at most [KEPT]: one a source
    // could not be asked for too, for [UNREACHABLE_FOR], so it is not asked again at every update.
    private val gathered = MutableStateFlow<Map<Gathered, Found>>(emptyMap())

    // The gathering under way, by what it is for: one at a time, shared by every row, page and write.
    private val inflight = ConcurrentHashMap<Gathered, Deferred<Found?>>()

    // The calls under way, by key, for *Stop writing*.
    private val jobs = ConcurrentHashMap<CardKey, Job>()

    // The insight another service, model or effort wrote, found in the cache once a key's own was not
    // (#911), by that key, at most [KEPT]: null when none was; no entry before the cache was read.
    private val others = MutableStateFlow<Map<CardKey, Entry?>>(emptyMap())

    // The play ([NowPlaying.play]) on which each item's own insight was last shown, by what it is about
    // and from, its model aside ([subject]): on that play, a model changed since holds back an
    // automatic write of its insight ([held]).
    private val seen = ConcurrentHashMap<CardKey, Long>()

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
                // The key it is kept under, with no AI service ready too (the model found unavailable,
                // #540): what is kept still shows (#911), though nothing is written.
                val key = evidence?.let { insights.keptKey(item, track, it, now) }
                when {
                    evidence == null -> flowOf(ItemShown.Gathering)
                    key == null -> flowOf(ItemShown.NoService)
                    else -> {
                        look(insights, key, track, item, evidence)
                        combine(shown, others, writing, settings) { entries, kept, asked, latest ->
                            val lifetime = latest?.historyLifetime
                            // Only once its own was looked for and not found (#911).
                            val earlier = if (key in kept) earlierFor(key, entries, kept[key], lifetime) else null
                            val play = track.nowPlaying.play
                            val own = entries[key]?.takeUnless { beforeSearch(it.state, evidence) }
                            val shown = shownFor(key, evidence, own, earlier, lifetime, key in asked, play)
                            if (insights.writes) shown else unwritable(shown)
                        }.onEach { seenOn(track, key, it) }
                    }
                }
            }
    }

    // On [play]: a write that failed or was stopped on an earlier play counts as no insight beside
    // another model's (#911), which shows again with *Rewrite insight*.
    @Suppress("LongParameterList") // The key, what it is shown from, and what it has.
    private fun shownFor(
        key: CardKey,
        evidence: ItemEvidence,
        entry: Entry?,
        earlier: Entry?,
        lifetime: Duration?,
        asked: Boolean,
        play: Long?,
    ): ItemShown =
        when {
            entry == null && asked -> ItemShown.Asking(key)
            entry == null -> earlier?.let { shownEarlier(key, evidence, it, failed = null) } ?: ItemShown.Offered
            expired(entry.writtenAt, lifetime) -> ItemShown.Offered.also { drop(key) }
            stale(entry, play) -> shownStale(key, evidence, entry, earlier, asked)
            // A rewrite that ended without an insight (#911): the earlier one again, saying why.
            entry.rewrites && earlier != null && failure(entry.state) != null ->
                shownEarlier(key, evidence, earlier, failure(entry.state))
            // The insight's evidence numbered as when it was written, but mapped to this track's lines:
            // an artist's or album's insight kept from another track cites the same ids (#522).
            else -> ItemShown.Shown(entry.state, evidence, modelName(key.model), key)
        }

    // [entry], a write under [key] that failed or was stopped on an earlier play ([stale], #911): asked
    // for again, being written; else [earlier], another model's insight, with *Rewrite insight*, as
    // though there were none; with none, as it ended.
    private fun shownStale(
        key: CardKey,
        evidence: ItemEvidence,
        entry: Entry,
        earlier: Entry?,
        asked: Boolean,
    ): ItemShown =
        when {
            asked -> ItemShown.Asking(key)
            earlier != null -> shownEarlier(key, evidence, earlier, failed = null)
            else -> ItemShown.Shown(entry.state, evidence, modelName(key.model), key)
        }

    // [shown] while no insight may be written, the selected model found unavailable (#540; with no AI
    // service for another reason, [ItemInsights.keptKey] gives none, so the row says so): nothing
    // offered, as with no service; an insight kept shows, with no *Rewrite insight* that could be
    // tapped (#911).
    private fun unwritable(shown: ItemShown): ItemShown =
        when (shown) {
            ItemShown.Offered, is ItemShown.Asking -> ItemShown.NoService
            is ItemShown.Shown -> shown.copy(canWrite = false)
            else -> shown
        }

    // [earlier], another service's, model's or effort's insight, shown under [key] with *Rewrite
    // insight* offered (#911): what a rewrite under [key] would use that it did not, and why the
    // last one [failed], if it did.
    private fun shownEarlier(
        key: CardKey,
        evidence: ItemEvidence,
        earlier: Entry,
        failed: String?,
    ): ItemShown {
        val by = (earlier.state as? InsightState.Written)?.writtenBy
        val with = key.writtenBy()
        val offer = by?.let { RewriteOffer(rewriteParts(it, with), with) }
        return ItemShown.Shown(earlier.state, evidence, by?.model?.let(modelName), key, offer, failed)
    }

    // Whether [entry] is a write that failed or was stopped on another play than [play] (#911): beside
    // another model's insight, no insight of its own; a tap still writes it again, nothing else does.
    private fun stale(
        entry: Entry,
        play: Long?,
    ): Boolean = failure(entry.state) != null && entry.play != play

    // Why a write ended without an insight, as the key insight's footer says it: stopped, or failed and why.
    private fun failure(state: InsightState): String? =
        when (state) {
            is InsightState.Failed -> state.reason
            InsightState.Stopped -> PipelineHost.STOPPED
            else -> null
        }

    // Whether [state], kept or shown, is one that found nothing to write from before a web search for
    // its item was due ([evidence] now has one due, #912: *AI web search* switched on, or a key given,
    // since), so it stands for none, and the item is offered.
    private fun beforeSearch(
        state: InsightState,
        evidence: ItemEvidence,
    ): Boolean = state == InsightState.NoEvidence && evidence.webDue

    // The insight another service, model or effort wrote for what [key]'s is about, from the same
    // evidence (#911): the most recently written of those shown ([entries]) and the one found in the
    // cache ([cached]), within *Keep history for* ([lifetime]); null for none.
    private fun earlierFor(
        key: CardKey,
        entries: Map<CardKey, Entry>,
        cached: Entry?,
        lifetime: Duration?,
    ): Entry? {
        val about = key.subject()
        val shownBefore =
            entries
                .filter { (other, entry) ->
                    other != key && other.subject() == about && entry.state is InsightState.Written
                }.values
        return (shownBefore + listOfNotNull(cached))
            .filterNot { expired(it.writtenAt, lifetime) }
            .maxByOrNull { it.writtenAt }
    }

    // [shown], under [key] on [track]: its own insight written, the play noted for what it is about (#911).
    private fun seenOn(
        track: TrackState,
        key: CardKey,
        shown: ItemShown,
    ) {
        val play = track.nowPlaying.play ?: return
        val own = shown as? ItemShown.Shown
        if (own?.state is InsightState.Written && own.rewriteWith == null) seen[key.subject()] = play
    }

    // Whether an automatic write of [key]'s insight on [track] is held back (#911): none of its own,
    // and on this play its row showed one another model wrote, before the model changed. It stays,
    // with *Rewrite insight*, as the key insight does after a settings change; nothing writes it unasked.
    private fun held(
        key: CardKey,
        track: TrackState,
    ): Boolean {
        val play = track.nowPlaying.play ?: return false
        val own = shown.value[key]
        return seen[key.subject()] == play && (own == null || stale(own, play))
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
            // What the write rewrites (#911, #972): the earlier insight shown in place of the key's own,
            // or again after a rewrite of it ended without one. Read before the claim, as the row showed it;
            // for a row not yet looked, only once its own is missed ([unlooked]).
            val replaces = key?.let { replaced(it, track) }
            if (evidence != null && key != null && claim(key, ask, track, evidence)) {
                write(insights, key, Asked(item, track, evidence, ask, work, now, replaces))
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
    override fun albumEvidence(track: TrackState): Flow<EvidenceSet?> = gatheredLines(track, InsightItem.ALBUM)

    // The artist's lines gathered for [track] and its artist as its card names it now (its entity), the
    // web's for an artist the sources say nothing about among them (#912).
    override fun artistEvidence(track: TrackState): Flow<EvidenceSet?> = gatheredLines(track, InsightItem.ARTIST)

    // [item]'s lines gathered for [track], and what it is about as it names it now (its entity).
    private fun gatheredLines(
        track: TrackState,
        item: InsightItem,
    ): Flow<EvidenceSet?> =
        combine(gathered, parts) { kept, made ->
            val entity = made?.let { insightsOf(it).entity(item, track) }
            kept.entries
                .lastOrNull { (at, _) ->
                    entity != null &&
                        at.item == item &&
                        at.lookupKey == track.normalized.lookupKey &&
                        at.entity == entity
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

    // An item asked for: what it is, of which track, from what evidence, how it was asked, and under
    // which settings its evidence was gathered.
    // [replaces] is what wrote the insight the write rewrites (#911, #972), null for none; given once the
    // write misses its own insight, when its row had not looked for the earlier one ([unlooked]).
    @Suppress("LongParameterList") // What was asked, and what it rewrites.
    private class Asked(
        val item: InsightItem,
        val track: TrackState,
        val evidence: ItemEvidence,
        val ask: ItemAsk,
        val work: CoroutineContext,
        val settings: Settings,
        val replaces: WrittenBy? = null,
    ) {
        fun with(found: ItemEvidence) = Asked(item, track, found, ask, work, settings, replaces)

        // As asked, rewriting what [by] wrote, unless it rewrites something already.
        fun replacing(by: WrittenBy?) =
            if (by == null || replaces != null) this else Asked(item, track, evidence, ask, work, settings, by)
    }

    // What wrote the insight a write under [key] on [track] rewrites (#911): the earlier insight's, in
    // place of none, of a rewrite (a tap's or an automatic one's, #972) that ended without one, or of
    // a write that did on an earlier play, while the row shows it; null otherwise. Writing in its place
    // is a rewrite of it, as a tap on *Rewrite insight* is.
    private fun replaced(
        key: CardKey,
        track: TrackState,
    ): WrittenBy? {
        if (!ownless(key, track) || key !in others.value) return null
        val earlier = earlierFor(key, shown.value, others.value[key], settings.value?.historyLifetime)
        return (earlier?.state as? InsightState.Written)?.writtenBy
    }

    // What an automatic write of [key] rewrites when its row has not looked for the earlier insight
    // (#972): read from the cache, once its own is missed, and kept as the row's look would keep it,
    // so the row shows it again if the write ends without an insight; null for a tap, which rewrites
    // only what the row shows ([replaced]).
    private suspend fun unlooked(
        insights: ItemInsights,
        key: CardKey,
        asked: Asked,
    ): WrittenBy? {
        if (asked.ask == ItemAsk.TAP || key in others.value || !ownless(key, asked.track)) return null
        val other = lookOther(insights, key, asked.evidence, asked.work)
        val earlier = earlierFor(key, shown.value, other, settings.value?.historyLifetime)
        return (earlier?.state as? InsightState.Written)?.writtenBy
    }

    // The insight another service, model or effort wrote for what [key]'s is about, from [evidence],
    // kept and within *Keep history for* (#911), kept in [others] for [key] as the row shows it, while
    // no *Clear history* came since [work] started; null for none.
    private suspend fun lookOther(
        insights: ItemInsights,
        key: CardKey,
        evidence: ItemEvidence,
        work: CoroutineContext,
    ): Entry? {
        val other =
            insights
                .keptByOther(key, evidence)
                ?.takeIf { !expired(it.writtenAt) }
                ?.let { Entry(it.state, it.writtenAt ?: clock.now(), evidence) }
        publish(work) { others.update { it.withOther(key, other) } }
        return other
    }

    // Whether [key] has no insight of its own on [track]: none, a rewrite that ended without one, or a
    // write that did on an earlier play ([stale]).
    private fun ownless(
        key: CardKey,
        track: TrackState,
    ): Boolean {
        val entry = shown.value[key]
        return entry == null || entry.rewrites || stale(entry, track.nowPlaying.play)
    }

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
                            ) { insights.evidence(item, track, now) }?.let { Found(it, clock.now(), track, now) }
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
        insights.searchesWeb(item, now),
    )

    // [first]'s insight, kept meanwhile (another track of the artist or album wrote it), with no call,
    // or written; each state shown while no *Clear history* came since it started. Before an album's
    // is written, its web search is made when due (#885, [searched]), which may give it another key.
    private suspend fun write(
        insights: ItemInsights,
        first: CardKey,
        firstAsked: Asked,
    ) {
        var key = first
        var asked = firstAsked
        val item = asked.item
        val track = asked.track
        val work = currentCoroutineContext()
        val job = work[Job]
        job?.let { jobs[key] = it }
        val show: (InsightState, Instant) -> Unit = { state, at ->
            // A tap's or an automatic one's (#972): one that ends without an insight shows the earlier
            // insight again, saying why.
            val entry = Entry(state, at, asked.evidence, asked.replaces != null, track.nowPlaying.play)
            publish(work) { shown.update { it.with(key, entry) } }
        }
        val question = Question(track, AiQuestion.Item(item), work)
        val counted: (QuestionCost) -> Unit = { more -> question.count(more, key) }
        try {
            val next =
                if (showKept(insights, key, asked)) {
                    Next(key, asked, AiOutcome.ANSWERED)
                } else {
                    // Its own missed: what it rewrites, when its row had not looked yet (#972), which
                    // [show] and every key the write moves to then see.
                    asked = asked.replacing(unlooked(insights, key, asked))
                    toWrite(insights, key, asked, job, counted)
                }
            key = next.key
            asked = next.asked
            finish(insights, first, next, Writing(question, counted, show))
        } catch (e: StopAsked) {
            // Stopped by the user (*Stop writing*): said so, offered again on a tap; nothing was kept.
            question.end(AiOutcome.STOPPED)
            show(InsightState.Stopped, clock.now())
            throw e
        } catch (e: CancellationException) {
            question.end(AiOutcome.STOPPED)
            throw e
        } catch (
            // A bug, not the network, which the writer maps to a state: shown as failed, offered again.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val reason = e::class.simpleName ?: FAILED
            question.end(AiOutcome.FAILED, reason)
            show(InsightState.Failed(reason), clock.now())
        } finally {
            writing.update { it - key }
            jobs.remove(key, job)
        }
    }

    // A write's question, how its costs are counted, and where its states are shown ([write]).
    private class Writing(
        val question: Question,
        val counted: (QuestionCost) -> Unit,
        val show: (InsightState, Instant) -> Unit,
    )

    // [next]'s write, [first]'s as [toWrite] left it: kept (under the key a search gave too), or
    // written by another, with no call, its search ended; written already, or failed, on the way
    // (the artist's search, #912), and shown; not asked, its gates closed while the evidence or the
    // cache was read, and offered again; or written now.
    private suspend fun finish(
        insights: ItemInsights,
        first: CardKey,
        next: Next,
        writing: Writing,
    ) {
        val key = next.key
        val asked = next.asked
        val question = writing.question
        val settled = next.ended ?: AiOutcome.ANSWERED.takeIf { key != first && showKept(insights, key, asked) }
        if (settled != null) {
            next.shown?.let { writing.show(it, (it as? InsightState.Written)?.writtenAt ?: clock.now()) }
            question.end(settled, (next.shown as? InsightState.Failed)?.reason)
            return
        }
        question.ask(key)
        val item = asked.item
        val last =
            next.takeUnless { it.notAsked }?.let {
                val gate = { mayCall(key, asked) }
                insights.write(
                    key,
                    item,
                    asked.track,
                    asked.evidence,
                    gate,
                    writing.counted,
                    asked.replaces,
                    writing.show,
                )
            }
        if (last == null) question.notAsked() else question.end(outcomeOf(last), (last as? InsightState.Failed)?.reason)
    }

    // An item's question on [track], for the diagnostics (#851) and [paid], in [work]: asked at its
    // first paid part, an album's or an artist's web search before its call (#885, #912), else the
    // call, so a search paid for with no call after it still ends in an event with its cost; ended once.
    private inner class Question(
        private val track: TrackState,
        private val question: AiQuestion,
        private val work: CoroutineContext,
    ) {
        private var started: Instant? = null
        private var cost = QuestionCost()

        // Asked, with [key]'s service and model, unless it is already.
        fun ask(key: CardKey) {
            if (started != null) return
            note(work, PipelineEvent.AiAsked(track.normalized.lookupKey, question, false, key.provider, key.model))
            started = clock.now()
        }

        // [more] paid for, asking it first.
        fun count(
            more: QuestionCost,
            key: CardKey,
        ) {
            ask(key)
            cost += more
            paid(more)
        }

        // Ended as [outcome], with what it cost, if it was asked.
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

        // Its call not made, the gates closed: not asked, unless a web search or a call was paid for
        // (the AI service's own searches stopped after its first call, #912), when it ends, with that
        // cost, as stopped.
        fun notAsked() {
            if (cost.searches > 0 || cost.calls > 0) {
                end(AiOutcome.STOPPED)
            } else {
                started = null
                note(work, PipelineEvent.AiNotAsked(track.normalized.lookupKey, question))
            }
        }
    }

    // What [key]'s write goes on with, its own insight not kept ([write] serves that first): [key] and
    // [asked], or, after an album's or an artist's web search (#885, #912, [searched]), the key and
    // evidence it then has, the claim moved to it; [Next.ended] when nothing is left to write, as its
    // question ended: stopped, the next key's written by another already, failed, the artist's search
    // failing with nothing else to write from, or written while the AI service searched
    // ([searchingWrite]).
    private suspend fun toWrite(
        insights: ItemInsights,
        key: CardKey,
        asked: Asked,
        job: Job?,
        counted: (QuestionCost) -> Unit,
    ): Next {
        // The key an album's or the artist's *Once* search's lines give, claimed beside [key] before
        // they are kept (#935).
        var reserved: CardKey? = null
        var taken = false
        val reserve = { next: CardKey -> reserve(key, next, job).also { if (it) reserved = next } }
        try {
            return when (val step = searched(key, asked, counted, reserve)) {
                null -> searchingWrite(insights, key, asked, job, counted) ?: Next(key, asked, null)
                WebStep.Failed ->
                    Next(key, asked, AiOutcome.FAILED, shown = InsightState.Failed(AiSearches.WEB_SEARCH_FAILED))
                // Its gates closed after the waits, before the search's request: not asked, offered again.
                WebStep.Refused -> Next(key, asked, null, notAsked = true)
                is WebStep.Found -> {
                    taken = step.key != key && step.key == reserved
                    moved(insights, key, asked.with(step.evidence), step.key, job, null, claimed = taken)
                }
            }
        } finally {
            // A reservation the write did not get to use is let go.
            reserved?.takeIf { it != key && !taken }?.let { release(it, job) }
        }
    }

    // A write's key and what was asked, and how its question ended when nothing is left to write
    // ([toWrite]), with the state to show then, if any; [notAsked] when its gates closed before the
    // AI service's own searches ([searchingWrite]).
    private class Next(
        val key: CardKey,
        val asked: Asked,
        val ended: AiOutcome?,
        val shown: InsightState? = null,
        val notAsked: Boolean = false,
    )

    // [asked], the write of [key] with the evidence a web search gave, moved to the key [next] that
    // evidence gives, unless another write of it is under way (another track of the album or artist),
    // which shows: kept as the item's evidence gathered, given to its other tracks ([spread]), its
    // question ended as [ended] says, with [shown], when the write is done already. [claimed] when
    // [next] was reserved by this write before its lines were kept (#935): [key]'s claim goes.
    @Suppress("LongParameterList") // The write, where it goes, and how it ended.
    private fun moved(
        insights: ItemInsights,
        key: CardKey,
        asked: Asked,
        next: CardKey,
        job: Job?,
        ended: AiOutcome?,
        shown: InsightState? = null,
        claimed: Boolean = false,
    ): Next {
        // No suspension from here to the caller's taking the next key, so a Stop finds it.
        val moved = if (claimed) true.also { release(key, job) } else move(key, next, job)
        // Kept once the claim moved, so the row goes from this key's to the next's, still asked.
        gatheredAgain(insights, asked)
        return if (moved) Next(next, asked, ended, shown) else Next(key, asked, AiOutcome.STOPPED)
    }

    // [asked]'s evidence kept as its item's gathered, and given to its other tracks ([spread]).
    private fun gatheredAgain(
        insights: ItemInsights,
        asked: Asked,
    ) {
        val at = gatheredFor(insights, asked.track, asked.item, asked.settings)
        keep(at, Found(asked.evidence, clock.now(), asked.track, asked.settings), asked.work)
        spread(insights, at, asked.evidence, asked.work)
    }

    // [key]'s artist insight written while the AI service searches the web for the artist itself
    // (#912, `ItemInsights.writeSearching`), when [asked]'s evidence calls for it under the settings
    // and parts read again now, as for an album's search ([searched]). Its gates ([mayCall], and web
    // search still allowed for it under the settings and parts then, [searchesNow]) are asked right
    // before each search and call: closed, nothing more is paid for, and it is not asked. The key its
    // pages give is claimed beside [key] before they are kept ([reserve]), so another track that
    // gathers them meanwhile finds it being written; then [key]'s claim goes, and the key and evidence
    // it ends with are taken as a search's are ([gatheredAgain]). Each search and call counted to
    // [counted]. Null when it does not apply: the write goes on as any other.
    private suspend fun searchingWrite(
        insights: ItemInsights,
        key: CardKey,
        asked: Asked,
        job: Job?,
        counted: (QuestionCost) -> Unit,
    ): Next? {
        val now = settings.value
        val current = parts.value?.let(::insightsOf)
        if (now == null || current == null || !current.searchesItself(asked.item, asked.evidence, now)) return null
        var reserved: CardKey? = null
        var handed = false
        val gates =
            WriteGates(
                mayPay = { mayCall(key, asked) && searchesNow(asked) },
                reserve = { next -> reserve(key, next, job).also { if (it) reserved = next } },
            )
        try {
            val result =
                withContext(io) {
                    current.writeSearching(
                        key,
                        asked.item,
                        asked.track,
                        now,
                        asked.evidence,
                        counted,
                        asked.replaces,
                        gates,
                    )
                }
            val with = if (result == null || result.evidence === asked.evidence) asked else asked.with(result.evidence)
            // Only evidence the write gave (pages, or "found nothing", kept): one that kept nothing
            // leaves the artist's gathered evidence, and its other tracks', with the search due.
            if (with !== asked) gatheredAgain(insights, with)
            handed = result != null
            return result?.let { Next(it.key, with, outcomeOf(it.state), it.state) }
                ?: Next(key, asked, null, notAsked = true)
        } finally {
            // [key]'s claim goes once the next is the write's; the next's, when the write did not get there.
            val other = reserved?.takeIf { it != key }
            if (other != null) release(if (handed) key else other, job)
        }
    }

    // Whether [asked]'s item is still written while the AI service searches (#912), under the settings
    // and the parts now: web search not switched off, nor its engine's key removed, since it began.
    private fun searchesNow(asked: Asked): Boolean {
        val now = settings.value ?: return false
        return parts.value?.let(::insightsOf)?.searchesItself(asked.item, asked.evidence, now) == true
    }

    // Whether [asked]'s item may still have a web search under the settings and the parts now (#912,
    // the album's #935): web search not switched off, nor its engine's key removed, since the write began.
    private fun searchesWebNow(asked: Asked): Boolean {
        val now = settings.value ?: return false
        return parts.value?.let(::insightsOf)?.searchesWeb(asked.item, now) == true
    }

    // [to] claimed beside [from], with the Stop of [job], unless it is being written already; [from]
    // itself is claimed already.
    private fun reserve(
        from: CardKey,
        to: CardKey,
        job: Job?,
    ): Boolean {
        if (from == to) return true
        var added = false
        writing.update { asked ->
            added = to !in asked
            asked + to
        }
        if (added) job?.let { jobs[to] = it }
        return added
    }

    // The claim on [key], and its Stop of [job], let go.
    private fun release(
        key: CardKey,
        job: Job?,
    ) {
        writing.update { it - key }
        jobs.remove(key, job)
    }

    // [key]'s insight kept in the cache, shown as [asked]'s, with no call; whether it was.
    private suspend fun showKept(
        insights: ItemInsights,
        key: CardKey,
        asked: Asked,
    ): Boolean {
        val kept =
            insights
                .kept(key, asked.evidence)
                ?.takeIf {
                    !expired(it.writtenAt) &&
                        it.state !is InsightState.Failed &&
                        !beforeSearch(it.state, asked.evidence)
                }
        if (kept != null) {
            val evidence = asked.evidence
            note(asked.work, kept(asked.track, key, AiQuestion.Item(asked.item), kept.writtenAt))
            val entry = Entry(kept.state, kept.writtenAt ?: clock.now(), evidence)
            publish(asked.work) { shown.update { it.with(key, entry) } }
        }
        return kept != null
    }

    // [key]'s album or artist insight's evidence with its web search made (#885, #912), while it is
    // due and the call it comes before may be made now ([mayCall]: the search is paid, as the call
    // is), with the key the insight then has; the caller keeps it as the item's evidence gathered, so
    // its row and *Sources* show the lines found. The settings and the parts are read again right
    // before the search, after any wait for the evidence or the cache: the card's key does not carry
    // web search's switch, its engine or its key, so the search goes by those now, through the
    // lookups built with them, and none is made once web search is off. Each request sent to the
    // engine is counted to [counted], and only those: not lines kept from an earlier search, nor an
    // album that cannot be checked; a request that fails or is cancelled once sent is still counted.
    // [WebStep.Failed] when the artist's search failed; [WebStep.Refused] when the gates closed after
    // the lookup's waits, before the request (#912, the album's #935); null when no search is due or
    // allowed, or none could be made (the album's), or the AI service makes it itself (the artist's
    // under *Up to 3 times*, [searchingWrite]); the insight is then written, or not, as before. The key
    // the lines a search found give (the album's, the artist's *Once*) is claimed with [reserve]
    // before they are kept (#935).
    private suspend fun searched(
        key: CardKey,
        asked: Asked,
        counted: (QuestionCost) -> Unit,
        reserve: (CardKey) -> Boolean,
    ): WebStep? =
        withContext(io) {
            val now = settings.value?.takeIf { asked.evidence.webDue && mayCall(key, asked) }
            val current = parts.value?.let(::insightsOf)
            val found =
                if (now != null && current != null) {
                    val gate = { mayCall(key, asked) && searchesWebNow(asked) }
                    val sent = { counted(QuestionCost.SEARCH) }
                    current.search(asked.item, asked.track, now, asked.evidence, sent, reserve, gate)
                } else {
                    ItemSearch.None
                }
            when (found) {
                ItemSearch.None -> null
                ItemSearch.Failed -> WebStep.Failed
                ItemSearch.Refused -> WebStep.Refused
                is ItemSearch.Found ->
                    now?.let { current?.key(asked.item, asked.track, found.evidence, it) }?.let { next ->
                        WebStep.Found(next, found.evidence)
                    }
            }
        }

    // The album's (or the artist's, #912) evidence gathered on its other tracks while its web search
    // was due, given the web's lines of [found], gathered for [from] by its search (#885), with no
    // search: each numbered as the item's, its ids mapped to its own track's lines
    // ([ItemInsights.withWebOf]), so a track opened earlier, its row shown again or its Sources, has
    // the lines and finds the insight the write keeps under the key they give, rather than its
    // earlier key, under which none is kept. In place, under the lock [forget] clears under, unless a
    // *Clear history* came since [work] began.
    private fun spread(
        insights: ItemInsights,
        from: Gathered,
        found: ItemEvidence,
        work: CoroutineContext,
    ) = publish(work) {
        gathered.update { kept ->
            kept.mapValues { (at, other) ->
                val same = at != from && at.item == from.item && at.item != InsightItem.SONG && at.entity == from.entity
                // Never the evidence of a search that kept nothing, with a search still due.
                val given =
                    if (same && other.evidence.webDue && !found.webDue) {
                        insights.withWebOf(other.track, other.settings, other.evidence, found)
                    } else {
                        null
                    }
                given?.let { Found(it, other.at, other.track, other.settings) } ?: other
            }
        }
    }

    // The claim on [from], being written, moved to [to], with the Stop of [job]; false, the claim on
    // [from] dropped, when [to] is being written already.
    private fun move(
        from: CardKey,
        to: CardKey,
        job: Job?,
    ): Boolean {
        if (from == to) return true
        var moved = false
        writing.update { asked ->
            moved = to !in asked
            if (moved) asked - from + to else asked - from
        }
        jobs.remove(from, job)
        if (moved) job?.let { jobs[to] = it }
        return moved
    }

    // How an item's question ended, as the diagnostics name it, from its last state.
    private fun outcomeOf(last: InsightState): AiOutcome =
        when (last) {
            is InsightState.Written -> AiOutcome.ANSWERED
            InsightState.NoEvidence -> AiOutcome.NOTHING_FOUND
            is InsightState.Failed -> AiOutcome.FAILED
            else -> AiOutcome.STOPPED
        }

    // [key]'s insight kept in the synthesis cache, read once, unless one is shown already (not a write
    // that failed or was stopped on an earlier play, [stale]); with none, the one another service,
    // model or effort wrote, if kept (#911), read only then.
    private fun look(
        insights: ItemInsights,
        key: CardKey,
        track: TrackState,
        item: InsightItem,
        evidence: ItemEvidence,
    ) {
        val play = track.nowPlaying.play
        val standing = shown.value[key]
        if ((standing != null && !stale(standing, play)) || !looked.add(key)) return
        tracked {
            val work = coroutineContext
            val kept =
                insights.kept(key, evidence)?.takeIf { !expired(it.writtenAt) && !beforeSearch(it.state, evidence) }
            if (kept == null) {
                lookOther(insights, key, evidence, work)
                return@tracked
            }
            val entry = Entry(kept.state, kept.writtenAt ?: clock.now(), evidence)
            publish(work) {
                var added = false
                // In place of none, or of a write that failed or was stopped on an earlier play.
                shown.update { kept ->
                    val other = kept[key]
                    val next = if (other != null && !stale(other, play)) kept else kept.with(key, entry)
                    next.also { added = it !== kept }
                }
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

    // Whether [key]'s insight on [track] may be asked for ([open]), and if so, marked as being written,
    // unless it is already. Not by itself or on opening while it is [held] (#911).
    private fun claim(
        key: CardKey,
        ask: ItemAsk,
        track: TrackState,
        evidence: ItemEvidence,
    ): Boolean {
        var added = false
        if (open(key, ask, track, evidence) && (ask == ItemAsk.TAP || !held(key, track))) {
            writing.update { asked ->
                added = key !in asked
                asked + key
            }
        }
        return added
    }

    // Whether [key]'s insight on [track] may be asked for: not shown, or past *Keep history for*; one
    // that failed or was stopped on this play, only on a tap, which nothing asks again by itself; one
    // that did on an earlier play ([stale], #911), as though there were none, by itself too. One that
    // found nothing to write from before a web search for it was due ([beforeSearch], #912), as none.
    private fun open(
        key: CardKey,
        ask: ItemAsk,
        track: TrackState,
        evidence: ItemEvidence,
    ): Boolean {
        val entry = shown.value[key]?.takeUnless { beforeSearch(it.state, evidence) }
        val again = ask == ItemAsk.TAP && (entry?.state is InsightState.Failed || entry?.state == InsightState.Stopped)
        return entry == null || again || expired(entry.writtenAt) || stale(entry, track.nowPlaying.play)
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
            others.value = emptyMap()
            seen.clear()
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
        ItemInsights(
            made.ai,
            made.cache,
            made.hasher,
            names,
            clock,
            made.albums,
            made.modelUnavailable,
            made.artistWeb,
            made.webSearch,
            made.dateForm,
        )

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

    // [this] with [other] for [key], last, the oldest dropped past [KEPT], and no longer counted as
    // looked for, so the cache is read again if it is asked for again.
    private fun Map<CardKey, Entry?>.withOther(
        key: CardKey,
        other: Entry?,
    ): Map<CardKey, Entry?> {
        val next = LinkedHashMap(this)
        next.remove(key)
        next[key] = other
        while (next.size > KEPT) {
            val oldest = next.keys.first()
            next.remove(oldest)
            if (oldest !in shown.value) looked.remove(oldest)
        }
        return next
    }

    // What [this] key's card is about and from, whatever service, model and effort write it (#911).
    private fun CardKey.subject(): CardKey = copy(provider = ProviderId.ANTHROPIC, model = "", effort = null)

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
