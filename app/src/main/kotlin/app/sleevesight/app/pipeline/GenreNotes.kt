package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.GenreListState
import app.sleevesight.core.pipeline.GenreLists
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.ReasonPath
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.pastKeepHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** *Why is this …?* in a genre's panel (#523), as the panel shows it. */
sealed interface GenreReason {
    /** Not offered: see [GenreReasons.path]. */
    data object None : GenreReason

    /** Offered: a tap writes it, a paid call, or two for a genre only MusicBrainz gives (#762). */
    data object Offered : GenreReason

    /**
     * Offered but not possible (#762): shown disabled, with [why] under it; a tap writes nothing. Not
     * for a reason the AI could not find once asked, which is [Shown] with [InsightState.NoEvidence].
     */
    data class Unavailable(
        val why: Unavailability,
    ) : GenreReason

    /**
     * Asked for, or kept from before: [state] is [InsightState.Writing] (with no text yet, while the
     * AI's genre list or the reason is looked for), [InsightState.Written], [InsightState.NoEvidence],
     * or [InsightState.Failed] (the list's call, or the reason's), beside which it is offered again.
     * [model] is the name of the model that writes it, for the credit under it.
     */
    data class Shown(
        val state: InsightState,
        val model: String? = null,
    ) : GenreReason
}

/** Why *Why is this …?* is shown disabled (#762), each with its own line under it. */
enum class Unavailability {
    /** No AI service ready ([ReasonPath.NoService]): "Enable an AI service in Settings → AI services." */
    NO_SERVICE,

    /**
     * Only MusicBrainz gives the genre, and the AI's genre list cannot run ([ReasonPath.CannotList]):
     * "No AI service enabled.", and what to switch on (`GenreLists.MODES`).
     */
    CANNOT_LIST,

    /** Only MusicBrainz gives the genre, and the AI's list does not have it ([ReasonPath.NotListed]). */
    NOT_LISTED,
}

/** The AI's genre list for a track (#762), as the panel shows it: [genres], by [model], its name. */
data class AiGenreList(
    val genres: List<GenreLabel>,
    val model: String,
)

/** [this] with [list]'s genres in its track's row (`TrackGenres.withAi`); itself for none. */
fun TrackState.withAi(list: AiGenreList?): TrackState =
    if (list == null || list.genres.isEmpty()) this else copy(genres = genres.withAi(list.genres, list.model))

/**
 * What a genre's panel shows besides its table (#523): what the genre is ([DefinitionFeed.definition],
 * asked again after a failure by [DefinitionFeed.retryDefinition], #786), and why the track has it
 * ([reason], written on a tap, [explain]).
 */
interface GenreNotes : DefinitionFeed {
    /**
     * The AI's genre list for [track] (#762), as it changes: null while none is known (not asked, or
     * not under the settings now).
     */
    fun aiGenres(track: TrackState): Flow<AiGenreList?> = flowOf(null)

    /** [track]'s reason for [genre], as it changes. */
    fun reason(
        track: TrackState,
        genre: String,
    ): Flow<GenreReason>

    /**
     * A tap on *Why is this …?* or its *Retry*: [track]'s reason for [genre] is written, unless it is
     * under way; for a genre only MusicBrainz gives, the AI's genre list is asked first (#762).
     */
    fun explain(
        track: TrackState,
        genre: String,
    )

    /** Neither: the panel shows its table alone (previews, tests). */
    object None : GenreNotes {
        override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

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
 * The app's [GenreNotes]: definitions from [lookups] ([AppGenreDefinitions], #786: fetched ahead,
 * one state per genre, shared by every panel); reasons written with the live pipeline's [parts] (its
 * AI service, synthesis cache and hasher, [GenreReasons]) under the [settings] as last read; for a
 * genre only MusicBrainz gives, the AI's genre list for the track asked first ([GenreLists], #762),
 * read against [vocabulary], and the reason written when the genre is on it, the models named as the
 * AI settings name them ([modelName]).
 *
 * A reason is kept with the track, as its insight is: in memory, by its card's key, so it shows
 * again with no call when the panel is closed and opened, another genre is opened in between, or a
 * page is left and opened again; and in the synthesis cache, read the first time the panel asks for
 * a reason ([reason]), so it shows again when the track plays again later. A settings change that
 * changes its key (another AI service, model or effort, new evidence) offers it again. Each is kept
 * in memory with when it was written (by [clock], the time the cache keeps it with too, or as the
 * cache says), and shown no longer than
 * *Keep history for* after it, as the cache serves it: read again on each change of the settings or
 * of the reasons shown, past it, it is dropped. The AI's genre list is kept the same way, by its own
 * key, and read from the cache the first time a panel asks for the track's ([aiGenres]) or for a
 * reason that needs it. The writing runs in [scope], the process's, so leaving the panel does not
 * stop a call paid for; it is marked with [epoch], and after *Clear history* ([forget]) nothing it
 * writes is shown or kept.
 */
@Suppress(
    "LongParameterList", // Where definitions, reasons and lists come from, where they run, and the time.
    "TooManyFunctions", // Each step of both paths, and its cache.
)
class AppGenreNotes private constructor(
    /** The definitions (#786): fetched ahead ([AppGenreDefinitions.fetchAhead]), their states. */
    val lookups: AppGenreDefinitions,
    private val vocabulary: GenreVocabulary,
    private val parts: StateFlow<PipelineParts?>,
    private val settings: StateFlow<Settings?>,
    private val scope: CoroutineScope,
    private val epoch: ClearEpoch,
    private val clock: Clock,
    private val modelName: (String) -> String,
) : GenreNotes,
    DefinitionFeed by lookups {
    /**
     * Definitions from [definitions] while *Show genres* and Wikipedia under *Sources* are on, off the
     * main thread ([io]), in [scope], marked with [epoch] ([AppGenreDefinitions]); reasons and the AI's
     * genre lists as above, read against [definitions]' vocabulary.
     */
    @Suppress("LongParameterList") // As the class's.
    constructor(
        definitions: GenreDefinitions,
        parts: StateFlow<PipelineParts?>,
        settings: StateFlow<Settings?>,
        scope: CoroutineScope,
        epoch: ClearEpoch = ClearEpoch(),
        io: CoroutineContext = EmptyCoroutineContext,
        clock: Clock = Clock.System,
        modelName: (String) -> String = { ModelNames.of(it) ?: it },
    ) : this(
        AppGenreDefinitions(definitions, settings, scope, epoch, io),
        definitions.vocabulary,
        parts,
        settings,
        scope,
        epoch,
        clock,
        modelName,
    )

    // A reason shown, or a list, and when it was written.
    private class Entry<S>(
        val state: S,
        val writtenAt: Instant,
    )

    // The reasons shown, by key, oldest first, at most [KEPT].
    private val shown = MutableStateFlow<Map<CardKey, Entry<InsightState>>>(emptyMap())

    // The AI's genre lists (#762), by key, oldest first, at most [KEPT].
    private val lists = MutableStateFlow<Map<CardKey, Entry<GenreListState>>>(emptyMap())

    // The genres whose *Why is this …?* was tapped while their list was asked, or when it failed, by
    // the list's key (#762): each followed by its reason once the list has it.
    private val waiting = MutableStateFlow<Map<CardKey, Set<String>>>(emptyMap())

    // The keys whose cached reason or list was looked for, and those being written.
    private val looked: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()
    private val writing: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()

    override fun aiGenres(track: TrackState): Flow<AiGenreList?> =
        combine(parts, settings) { made, now ->
            if (made == null ||
                now == null
            ) {
                null
            } else {
                listsOf(made).key(track, now)
            }
        }.distinctUntilChanged()
            .onEach { key -> key?.let { lookList(it) } }
            .combine(
                combine(lists, settings) { known, now -> known to now?.historyLifetime },
            ) { key, (known, lifetime) ->
                key?.let { listed(it, known, lifetime) }?.let { AiGenreList(it.genres, modelName(it.model)) }
            }.distinctUntilChanged()

    override fun reason(
        track: TrackState,
        genre: String,
    ): Flow<GenreReason> {
        // What a tap does, as the list known now has it.
        val paths =
            combine(parts, settings, lists) { made, now, known ->
                if (made == null || now == null) ReasonPath.None else path(made, now, known, track, genre)
            }
        return paths
            .distinctUntilChanged()
            .onEach { path ->
                when (path) {
                    is ReasonPath.Reason -> look(path.key, track)
                    is ReasonPath.Listing -> lookList(path.key)
                    else -> Unit
                }
            }.combine(
                combine(shown, lists, waiting, settings) { reasons, known, asked, now ->
                    Shown(reasons, known, asked, now?.historyLifetime)
                },
            ) { path, now -> shownFor(path, genre, now) }
    }

    // What the panel's states are made of: the reasons, the lists, the genres waiting on each, and
    // *Keep history for*.
    private class Shown(
        val reasons: Map<CardKey, Entry<InsightState>>,
        val known: Map<CardKey, Entry<GenreListState>>,
        val waiting: Map<CardKey, Set<String>>,
        val lifetime: Duration?,
    )

    // What the panel shows for [genre]'s [path], as [now] has it.
    private fun shownFor(
        path: ReasonPath,
        genre: String,
        now: Shown,
    ): GenreReason =
        when (path) {
            ReasonPath.None -> {
                GenreReason.None
            }

            ReasonPath.NoService -> {
                GenreReason.Unavailable(Unavailability.NO_SERVICE)
            }

            ReasonPath.CannotList -> {
                GenreReason.Unavailable(Unavailability.CANNOT_LIST)
            }

            ReasonPath.NotListed -> {
                GenreReason.Unavailable(Unavailability.NOT_LISTED)
            }

            is ReasonPath.Listing -> {
                val asked = genre in now.waiting[path.key].orEmpty()
                listShown(path.key, now.known[path.key].takeIf { asked }, now.lifetime)
            }

            is ReasonPath.Reason -> {
                val entry = now.reasons[path.key]
                when {
                    entry == null -> GenreReason.Offered
                    expired(entry.writtenAt, now.lifetime) -> GenreReason.Offered.also { drop(path.key) }
                    else -> GenreReason.Shown(entry.state, modelName(path.key.model))
                }
            }
        }

    // [key]'s list ([entry]), while the path still asks for it: offered, none known, not asked for
    // this genre, or past *Keep history for* ([lifetime]); looking; or failed, offered again.
    private fun listShown(
        key: CardKey,
        entry: Entry<GenreListState>?,
        lifetime: Duration?,
    ): GenreReason =
        when {
            entry == null -> GenreReason.Offered
            expired(entry.writtenAt, lifetime) -> GenreReason.Offered.also { dropList(key) }
            entry.state is GenreListState.Failed -> GenreReason.Shown(InsightState.Failed(entry.state.reason))
            // Asked, or listed just now, before the path follows it.
            else -> GenreReason.Shown(InsightState.Writing(""))
        }

    override fun explain(
        track: TrackState,
        genre: String,
    ) {
        val made = parts.value ?: return
        val now = settings.value ?: return
        when (val path = path(made, now, lists.value, track, genre)) {
            // Not shown, shown as failed, or past *Keep history for*; and not under way.
            is ReasonPath.Reason -> {
                if (open(path.key) && writing.add(path.key)) {
                    scope.launch(epoch.mark()) { write(reasonsOf(made), path.key, track, label(track, genre)) }
                }
            }

            // Asked for this genre too while the list is under way; asked, unless known.
            is ReasonPath.Listing -> {
                val underWay = path.key in writing
                if (underWay || openList(path.key)) {
                    synchronized(lock) { waiting.update { it + (path.key to (it[path.key].orEmpty() + genre)) } }
                    if (writing.add(path.key)) scope.launch(epoch.mark()) { list(made, path.key, track) }
                }
            }

            else -> Unit
        }
    }

    // What a tap on [genre]'s *Why is this …?* does for [track] under [now], the lists [known].
    private fun path(
        made: PipelineParts,
        now: Settings,
        known: Map<CardKey, Entry<GenreListState>>,
        track: TrackState,
        genre: String,
    ): ReasonPath {
        val lists = listsOf(made)
        val listed = lists.key(track, now)?.let { listed(it, known, now.historyLifetime) }
        return reasonsOf(made).path(track, genre, now, lists, listed?.genres?.map { it.identity }?.toSet())
    }

    // [key]'s list in [known], when listed and not past *Keep history for* ([lifetime]).
    private fun listed(
        key: CardKey,
        known: Map<CardKey, Entry<GenreListState>>,
        lifetime: Duration?,
    ): GenreListState.Listed? = known[key]?.takeIf { !expired(it.writtenAt, lifetime) }?.state as? GenreListState.Listed

    // [genre]'s label, as the chip shows it, with the AI's list known for [track].
    private fun label(
        track: TrackState,
        genre: String,
    ): String {
        val made = parts.value
        val now = settings.value
        val list =
            if (made == null || now == null) {
                null
            } else {
                listsOf(made).key(track, now)?.let { listed(it, lists.value, now.historyLifetime) }
            }
        return (track.genres.all.map { it.genre } + list?.genres.orEmpty())
            .firstOrNull { it.identity == genre }
            ?.label ?: genre
    }

    private fun reasonsOf(made: PipelineParts) = GenreReasons(made.ai, made.cache, made.hasher, clock)

    private fun listsOf(made: PipelineParts) = GenreLists(made.ai, made.cache, made.hasher, vocabulary, clock)

    // Whether [key]'s reason may be asked for: not shown, shown as failed, or past *Keep history for*.
    private fun open(key: CardKey): Boolean {
        val entry = shown.value[key]
        return entry == null || entry.state is InsightState.Failed || expired(entry.writtenAt)
    }

    // Whether [key]'s list may be asked for: not known, failed, or past *Keep history for*.
    private fun openList(key: CardKey): Boolean {
        val entry = lists.value[key]
        return entry == null || entry.state is GenreListState.Failed || expired(entry.writtenAt)
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
        ) -> Unit = { state, at -> publish(work) { shown.update { it.with(key, Entry(state, at)) } } }
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

    // Path B (#762): [key]'s list for [track], kept meanwhile with no call, or asked; then, with
    // [genre] on it, its reason, kept or written. Each state shown while no *Clear history* came since
    // it started. The reason is marked as being written before the list shows, so the panel goes from
    // looking to writing with no *Why is this …?* between.
    private suspend fun list(
        made: PipelineParts,
        key: CardKey,
        track: TrackState,
    ) {
        val work = currentCoroutineContext()
        val asker = listsOf(made)
        val reasons = reasonsOf(made)
        var next: List<Pair<CardKey, String>> = emptyList()
        val show: (GenreListState, Instant) -> Unit = { state, at ->
            publish(work) {
                if (state is GenreListState.Listed) {
                    val asked = waiting.value[key].orEmpty()
                    waiting.update { it - key }
                    next = asked.mapNotNull { genre -> follow(reasons, state, track, genre, at)?.let { it to genre } }
                }
                lists.update { it.with(key, Entry(state, at)) }
            }
        }
        try {
            val kept = asker.kept(key)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) show(kept.state, kept.writtenAt ?: clock.now()) else asker.ask(key, track, show)
        } catch (e: CancellationException) {
            throw e
        } catch (
            // A bug, not the network, which the list maps to a state: shown as failed, offered again.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            show(GenreListState.Failed(e::class.simpleName ?: FAILED), clock.now())
        } finally {
            writing.remove(key)
        }
        // Each genre asked for, on the list, written; none after a *Clear history* since the list
        // was asked, which drops their claims.
        if (epoch.isCurrent(work)) {
            coroutineScope {
                for ((reason, genre) in next) launch { write(reasons, reason, track, label(track, genre)) }
            }
        } else {
            next.forEach { (reason, _) -> writing.remove(reason) }
        }
    }

    // The key of [genre]'s reason to write after [listed] for [track], marked as being written at
    // [at]; null when it is not on the list, or is shown or under way already.
    private fun follow(
        reasons: GenreReasons,
        listed: GenreListState.Listed,
        track: TrackState,
        genre: String,
        at: Instant,
    ): CardKey? {
        val ids = listed.genres.map { it.identity }.toSet()
        val key =
            settings.value
                ?.takeIf { genre in ids }
                ?.let { reasons.key(track, genre, it, ids) }
                ?.takeIf { open(it) && writing.add(it) }
        key?.let { claimed -> shown.update { it.with(claimed, Entry(InsightState.Writing(""), at)) } }
        return key
    }

    /**
     * Forgets the reasons shown and the AI's genre lists, for *Clear history* (#547), and the
     * definitions' states, their lookups under way cancelled (#786); the cache's are deleted with it.
     * After the epoch's advance: a lookup that checked it before shows its state before this clears
     * it, never after.
     */
    fun forget() {
        synchronized(lock) {
            shown.value = emptyMap()
            lists.value = emptyMap()
            waiting.value = emptyMap()
            looked.clear()
        }
        lookups.forget()
    }

    // Guards each reason or list shown against *Clear history* ([forget]).
    private val lock = Any()

    /** For tests: runs between a state's check and its showing ([publish]). */
    @Volatile
    internal var beforePublish: () -> Unit = {}

    // Runs [show], a reason or a list shown, if no *Clear history* came since [work] started,
    // checked under the lock [forget] clears under: so its clear, which comes after the epoch's
    // advance, either comes after the state shown, and clears it, or before the check, which then
    // fails. As `ShownSearches.publish`.
    private fun publish(
        work: CoroutineContext,
        show: () -> Unit,
    ) = synchronized(lock) {
        if (epoch.isCurrent(work)) {
            beforePublish()
            show()
        }
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
            val kept = reasonsOf(made).kept(key, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                val entry = Entry(kept.state, kept.writtenAt ?: clock.now())
                publish(work) { shown.update { if (key in it) it else it.with(key, entry) } }
            }
        }
    }

    // The AI's genre list kept in the synthesis cache for [key] (#762), read once, unless one is known.
    private fun lookList(key: CardKey) {
        if (key in lists.value || !looked.add(key)) return
        val made = parts.value ?: return
        scope.launch(epoch.mark()) {
            val work = coroutineContext
            val kept = listsOf(made).kept(key)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                val entry = Entry<GenreListState>(kept.state, kept.writtenAt ?: clock.now())
                publish(work) { lists.update { if (key in it) it else it.with(key, entry) } }
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

    // Drops [key]'s list, past *Keep history for*, so the cache is read again (and misses).
    private fun dropList(key: CardKey) {
        lists.update { it - key }
        looked.remove(key)
    }

    // [this] with [entry] for [key], last, the oldest dropped past [KEPT], and no longer counted as
    // looked for in the cache.
    private fun <S> Map<CardKey, Entry<S>>.with(
        key: CardKey,
        entry: Entry<S>,
    ): Map<CardKey, Entry<S>> {
        val next = LinkedHashMap(this)
        next.remove(key)
        next[key] = entry
        while (next.size > KEPT) {
            // Dropped from memory, so read from the cache again when it is asked for.
            val oldest = next.keys.first()
            next.remove(oldest)
            looked.remove(oldest)
        }
        return next
    }

    private companion object {
        /** Enough for the genres of the last tracks played; the cache keeps the rest. */
        const val KEPT = 64

        const val FAILED = "failed"
    }
}
