package app.sleevesight.app.pipeline

import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.pastKeepHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** *Why is this …?* in a genre's panel (#523), as the panel shows it. */
sealed interface GenreReason {
    /** Not offered: see [GenreReasons.key]. */
    data object None : GenreReason

    /** Offered: a tap writes it, a paid call. */
    data object Offered : GenreReason

    /**
     * Offered but for D12, for a genre only MusicBrainz gives ([GenreReasons.withheld]): shown
     * disabled, with why (#721); a tap writes nothing.
     */
    data object Withheld : GenreReason

    /**
     * Asked for, or kept from before: [state] is [InsightState.Writing], [InsightState.Written],
     * [InsightState.NoEvidence], or [InsightState.Failed], beside which it is offered again.
     */
    data class Shown(
        val state: InsightState,
    ) : GenreReason
}

/**
 * What a genre's panel shows besides its table (#523): what the genre is ([definition]), and why
 * the track has it ([reason], written on a tap, [explain]).
 */
interface GenreNotes {
    /**
     * The definition of the genre [genre] stands for (`GenreLabel.identity`), as it changes: found;
     * not found when it has none, which the panel says (#663); failed when the lookup failed; null
     * while it is looked up and while lookups are off; looked up again when they come on.
     */
    fun definition(genre: String): Flow<Lookup<GenreDefinition>?>

    /** [track]'s reason for [genre], as it changes. */
    fun reason(
        track: TrackState,
        genre: String,
    ): Flow<GenreReason>

    /** A tap on *Why is this …?*: [track]'s reason for [genre] is written, unless it is under way. */
    fun explain(
        track: TrackState,
        genre: String,
    )

    /** Neither: the panel shows its table alone (previews, tests). */
    object None : GenreNotes {
        override fun definition(genre: String): Flow<Lookup<GenreDefinition>?> = flowOf(null)

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(GenreReason.None)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit
    }
}

/**
 * The app's [GenreNotes]: definitions from [definitions] while *Show genres* and Wikipedia under
 * *Sources* are on (its lookups behind the same switch), looked up again when they come on, off the
 * main thread ([io]); reasons written with the live pipeline's [parts] (its AI service, synthesis
 * cache and hasher, [GenreReasons]) under the [settings] as last read.
 *
 * A reason is kept with the track, as its insight is: in memory, by its card's key, so it shows
 * again with no call when the panel is closed and opened, another genre is opened in between, or a
 * page is left and opened again; and in the synthesis cache, read the first time the panel asks for
 * a reason ([reason]), so it shows again when the track plays again later. A settings change that
 * changes its key (another AI service, model or effort, new evidence) offers it again. Each is kept
 * in memory with when it was written (by [clock], the time the cache keeps it with too, or as the
 * cache says), and shown no longer than
 * *Keep history for* after it, as the cache serves it: read again on each change of the settings or
 * of the reasons shown, past it, it is dropped. The writing runs in [scope], the process's, so
 * leaving the panel does not stop a call paid for; it is marked with [epoch], and after *Clear
 * history* ([forget]) nothing it writes is shown or kept.
 */
@Suppress("LongParameterList") // Where definitions and reasons come from, where they run, and the time.
class AppGenreNotes(
    private val definitions: GenreDefinitions,
    private val parts: StateFlow<PipelineParts?>,
    private val settings: StateFlow<Settings?>,
    private val scope: CoroutineScope,
    private val epoch: ClearEpoch = ClearEpoch(),
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val clock: Clock = Clock.System,
) : GenreNotes {
    // A reason's key, null when none is offered, and whether it is withheld (D12).
    private data class Offer(
        val key: CardKey?,
        val withheld: Boolean,
    )

    // A reason shown, and when it was written.
    private class Entry(
        val state: InsightState,
        val writtenAt: Instant,
    )

    // The reasons shown, by key, oldest first, at most [KEPT].
    private val shown = MutableStateFlow<Map<CardKey, Entry>>(emptyMap())

    // The keys whose cached reason was looked for, and those being written.
    private val looked: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()
    private val writing: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun definition(genre: String): Flow<Lookup<GenreDefinition>?> =
        settings
            .map { now -> now != null && now.showGenres && now.isEnabled(SourceId.WIKIPEDIA) }
            .distinctUntilChanged()
            .mapLatest { on -> if (on) withContext(io) { definitions.lookup(genre) } else null }

    override fun reason(
        track: TrackState,
        genre: String,
    ): Flow<GenreReason> {
        // The reason's key, or null when none is offered, and whether it is withheld (D12).
        val keys =
            combine(parts, settings) { made, now ->
                val reasons = made?.let { GenreReasons(it.ai, it.cache, it.hasher, clock) }
                if (now == null || reasons == null) {
                    Offer(null, withheld = false)
                } else {
                    Offer(reasons.key(track, genre, now), reasons.withheld(track, genre, now))
                }
            }
        return keys
            .distinctUntilChanged()
            .onEach { offer -> offer.key?.let { look(it, track) } }
            .combine(
                combine(shown, settings) { reasons, now -> reasons to now?.historyLifetime },
            ) { (key, withheld), (reasons, lifetime) ->
                val entry = key?.let { reasons[it] }
                when {
                    withheld -> GenreReason.Withheld
                    key == null -> GenreReason.None
                    entry == null -> GenreReason.Offered
                    expired(entry.writtenAt, lifetime) -> GenreReason.Offered.also { drop(key) }
                    else -> GenreReason.Shown(entry.state)
                }
            }
    }

    override fun explain(
        track: TrackState,
        genre: String,
    ) {
        val reasons = parts.value?.let { GenreReasons(it.ai, it.cache, it.hasher, clock) } ?: return
        val key = settings.value?.let { now -> reasons.key(track, genre, now) }
        // Not offered; shown already, unless it failed or has expired; or under way.
        if (key == null || !open(key) || !writing.add(key)) return
        val label =
            track.genres.all
                .firstOrNull { it.genre.identity == genre }
                ?.genre
                ?.label ?: genre
        scope.launch(epoch.mark()) { write(reasons, key, track, label) }
    }

    // Whether [key]'s reason may be asked for: not shown, shown as failed, or past *Keep history for*.
    private fun open(key: CardKey): Boolean {
        val entry = shown.value[key]
        return entry == null || entry.state is InsightState.Failed || expired(entry.writtenAt)
    }

    // [key]'s reason, kept meanwhile (another panel's), with no call, or written; each state shown
    // while no *Clear history* came since it started.
    private suspend fun write(
        reasons: GenreReasons,
        key: CardKey,
        track: TrackState,
        label: String,
    ) {
        val work = currentCoroutineContext()
        val show: (
            InsightState,
            Instant,
        ) -> Unit = { state, at -> if (epoch.isCurrent(work)) shown.update { it.with(key, Entry(state, at)) } }
        try {
            val kept = reasons.kept(key, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                show(kept.state, kept.writtenAt ?: clock.now())
            } else {
                reasons.write(key, track, label, show)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // A bug, not the network, which the writer maps to a state: shown as failed, offered again.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            show(InsightState.Failed(e::class.simpleName ?: FAILED), clock.now())
        } finally {
            writing.remove(key)
        }
    }

    /** Forgets the reasons shown, for *Clear history* (#547); the cache's are deleted with it. */
    fun forget() {
        shown.value = emptyMap()
        looked.clear()
    }

    // The reason kept in the synthesis cache for [key], read once, unless one is shown already.
    private fun look(
        key: CardKey,
        track: TrackState,
    ) {
        if (key in shown.value || !looked.add(key)) return
        val made = parts.value ?: return
        scope.launch(epoch.mark()) {
            val work = coroutineContext
            val kept =
                GenreReasons(
                    made.ai,
                    made.cache,
                    made.hasher,
                    clock,
                ).kept(key, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null && epoch.isCurrent(work)) {
                val entry = Entry(kept.state, kept.writtenAt ?: clock.now())
                shown.update { if (key in it) it else it.with(key, entry) }
            }
        }
    }

    // Whether a reason written at [writtenAt] is past *Keep history for* ([lifetime]; null, for ever).
    private fun expired(
        writtenAt: Instant?,
        lifetime: Duration? = settings.value?.historyLifetime,
    ): Boolean = pastKeepHistory(writtenAt, lifetime, clock.now())

    // Drops [key]'s reason, past *Keep history for*, so the cache is read again (and misses).
    private fun drop(key: CardKey) {
        shown.update { it - key }
        looked.remove(key)
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

    private companion object {
        /** Enough for the genres of the last tracks played; the cache keeps the rest. */
        const val KEPT = 64

        const val FAILED = "failed"
    }
}
