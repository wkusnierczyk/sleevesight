package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.AiOutcome
import app.sleevesight.core.pipeline.AiQuestion
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.GenreListState
import app.sleevesight.core.pipeline.GenreLists
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.ReasonPath
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.pastKeepHistory
import app.sleevesight.core.synthesis.GenreListMode
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
import kotlin.coroutines.AbstractCoroutineContextElement
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
     * [model] is the name of the model that writes it, for the credit under it. A reason written
     * before this play of the track, served from what was kept, has [keptAt], when it was written
     * (#851); null for one written on this play. [couldHelp] is what is switched off in the settings
     * and could explain it (#695: *AI service knowledge*, *AI web search* as *Settings* shows it, *Off*
     * with Exa web search off too), in the order the panel names them, which the panel offers to
     * switch on under "AI could not find an explanation." ([InsightState.NoEvidence]); empty with both
     * on (*AI web search* used but with no search engine to run it included), and for every other state.
     */
    data class Shown(
        val state: InsightState,
        val model: String? = null,
        val keptAt: Instant? = null,
        val couldHelp: List<GenreListMode> = emptyList(),
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
    val sources: List<String> = emptyList(),
)

/** [this] with [list]'s genres in its track's row (`TrackGenres.withAi`); itself for none. */
fun TrackState.withAi(list: AiGenreList?): TrackState =
    if (list == null ||
        list.genres.isEmpty()
    ) {
        this
    } else {
        copy(genres = genres.withAi(list.genres, list.model, list.sources))
    }

/**
 * What a genre's panel shows besides its table (#523): what the genre is ([DefinitionFeed.definition],
 * asked again after a failure by [DefinitionFeed.retryDefinition], #786), and why the track has it
 * ([reason], written on a tap, [explain]).
 */
interface GenreNotes : DefinitionFeed {
    /**
     * The genre list (`genre-vocabulary.json`) the names in an artist's description are matched
     * against (#1000, `describedGenres`); null for none, and no genre is read from the text.
     */
    val vocabulary: GenreVocabulary? get() = null

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
 *
 * Each reason or list asked of the AI service, and each served from the cache, is told to [events],
 * the diagnostics' log (#851), as `AppAiSearches` tells its questions: an [PipelineEvent.AiAsked] and
 * an [PipelineEvent.AiEnded] around the asking (whose calls may be none, when what was kept answered
 * it after all), an [PipelineEvent.AiKept] for one read from the cache and shown; none for work
 * started before a *Clear history*. A reason shown again from memory is not told again.
 *
 * A reason is shown as kept from before (#851, `GenreReason.Shown.keptAt`) when it was written
 * before the play of its track began ([plays], `Plays`: observed, so it is worked out again when the
 * play changes); for a track not playing (no time), when
 * it was read from the cache.
 */
@Suppress(
    "LongParameterList", // Where definitions, reasons and lists come from, where they run, and the time.
    "TooManyFunctions", // Each step of both paths, and its cache.
)
class AppGenreNotes private constructor(
    /** The definitions (#786): fetched ahead ([AppGenreDefinitions.fetchAhead]), their states. */
    val lookups: AppGenreDefinitions,
    override val vocabulary: GenreVocabulary,
    private val parts: StateFlow<PipelineParts?>,
    private val settings: StateFlow<Settings?>,
    private val scope: CoroutineScope,
    private val epoch: ClearEpoch,
    private val clock: Clock,
    private val modelName: (String) -> String,
    private val paid: (QuestionCost) -> Unit,
    private val events: (PipelineEvent) -> Unit,
    private val plays: StateFlow<Play?>,
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
        paid: (QuestionCost) -> Unit = {},
        events: (PipelineEvent) -> Unit = {},
        plays: StateFlow<Play?> = MutableStateFlow(null),
    ) : this(
        AppGenreDefinitions(definitions, settings, scope, epoch, io),
        definitions.vocabulary,
        parts,
        settings,
        scope,
        epoch,
        clock,
        modelName,
        paid,
        events,
        plays,
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
            .onEach { key -> key?.let { lookList(it, track) } }
            .combine(
                combine(lists, settings) { known, now -> known to now?.historyLifetime },
            ) { key, (known, lifetime) ->
                key
                    ?.let {
                        listed(
                            it,
                            known,
                            lifetime,
                        )
                    }?.let { AiGenreList(it.genres, modelName(it.model), it.sources) }
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
                    is ReasonPath.Reason -> look(path.key, track, genre)
                    is ReasonPath.Listing -> lookList(path.key, track)
                    else -> Unit
                }
            }.combine(
                combine(shown, lists, waiting, settings, plays) { reasons, known, asked, now, play ->
                    Shown(reasons, known, asked, now?.historyLifetime, play, now)
                },
            ) { path, now -> shownFor(path, track, genre, now) }
    }

    // What the panel's states are made of: the reasons, the lists, the genres waiting on each,
    // *Keep history for*, the play now (#851), and the settings, for what is off (#695).
    private class Shown(
        val reasons: Map<CardKey, Entry<InsightState>>,
        val known: Map<CardKey, Entry<GenreListState>>,
        val waiting: Map<CardKey, Set<String>>,
        val lifetime: Duration?,
        val play: Play?,
        val settings: Settings?,
    )

    // What the panel shows for [track]'s [genre]'s [path], as [now] has it.
    private fun shownFor(
        path: ReasonPath,
        track: TrackState,
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
                    else ->
                        GenreReason.Shown(
                            entry.state,
                            modelName(path.key.model),
                            keptAt(entry, track, now.play),
                            couldHelp(now.settings).takeIf { entry.state == InsightState.NoEvidence }.orEmpty(),
                        )
                }
            }
        }

    // What could explain a reason that is switched off in [settings] (#695): *AI service knowledge*,
    // and *AI web search* as *Settings* shows it (`aiWebSearchUsed`): *Off*, set so or with Exa web
    // search off, where the setting says Exa is needed. Not *AI web search* used but with no web
    // search engine to run it; none with no settings read.
    private fun couldHelp(settings: Settings?): List<GenreListMode> =
        if (settings == null) {
            emptyList()
        } else {
            GenreLists.MODES.filter { mode ->
                when (mode) {
                    GenreListMode.KNOWLEDGE -> !settings.isEnabled(SourceId.MODEL)
                    GenreListMode.WEB -> settings.aiWebSearchUsed == AiWebSearch.OFF
                }
            }
        }

    // When [entry] was written, if it is a reason kept from before for [track] (#851): written, and
    // before [track]'s [play] began, or, with no time for it, read from the cache.
    private fun keptAt(
        entry: Entry<InsightState>,
        track: TrackState,
        play: Play?,
    ): Instant? {
        val state = entry.state as? InsightState.Written ?: return null
        val began = play?.beganFor(track)
        val kept = if (began != null) entry.writtenAt < began else state.cached
        return entry.writtenAt.takeIf { kept }
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
                    scope.launch(mark()) { write(reasonsOf(made), path.key, track, label(track, genre)) }
                }
            }

            // Asked for this genre too while the list is under way; asked, unless known.
            is ReasonPath.Listing -> {
                val underWay = path.key in writing
                if (underWay || openList(path.key)) {
                    synchronized(lock) { waiting.update { it + (path.key to (it[path.key].orEmpty() + genre)) } }
                    if (writing.add(path.key)) scope.launch(mark()) { list(made, path.key, track) }
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

    // [genre]'s label, as the chip shows it, with the AI's list known for [track]: one an artist's
    // description names too (#1000).
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
        return (track.genres.everyLabel.map { it.genre } + list?.genres.orEmpty())
            .firstOrNull { it.identity == genre }
            ?.label ?: genre
    }

    private fun reasonsOf(made: PipelineParts) =
        GenreReasons(made.ai, made.cache, made.hasher, clock, made.webSearch, made.dateForm)

    private fun listsOf(made: PipelineParts) =
        GenreLists(made.ai, made.cache, made.hasher, vocabulary, clock, made.webSearch)

    // How a reason or a list searches the web (#801), as the settings last read say; by a tap.
    private fun asking(): AiSearches.Asking = AiSearches.Asking.of(settings.value, automatic = false)

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

    // [key]'s reason, kept meanwhile (another panel's, or from the evidence alone under another *AI
    // web search*, `GenreReasons.found`), with no call, or written; each state shown while no *Clear
    // history* came since it started.
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
        val question = AiQuestion.Reason(label)
        val asked = Asked(work, track, key, question)
        try {
            val kept = reasons.found(key, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                synchronized(lock) {
                    if (current(work)) tellKept(track, key, kept(track, key, question, kept.writtenAt))
                }
                show(kept.state, kept.writtenAt ?: clock.now())
            } else {
                asked.start()
                val end =
                    reasons.write(key, track, label, asking(), asked.counted, fresh = { !expired(it) }, show = show)
                asked.end(
                    when (end) {
                        is InsightState.Written -> AiOutcome.ANSWERED
                        InsightState.NoEvidence -> AiOutcome.NOTHING_FOUND
                        is InsightState.Failed -> AiOutcome.FAILED
                        else -> AiOutcome.STOPPED
                    },
                    (end as? InsightState.Failed)?.reason,
                )
            }
        } catch (e: CancellationException) {
            asked.end(AiOutcome.STOPPED)
            throw e
        } catch (
            // A bug, not the network, which the writer maps to a state: shown as failed, offered again.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val reason = e::class.simpleName ?: FAILED
            asked.end(AiOutcome.FAILED, reason)
            show(InsightState.Failed(reason), clock.now())
        } finally {
            writing.remove(key)
        }
    }

    // A question of [key] for [track] asked of the AI service (#851), told to the log as it starts
    // ([start]) and ends ([end]), with what it cost ([counted], counted to [paid] too) and how long
    // it took; no end without a start.
    private inner class Asked(
        private val work: CoroutineContext,
        private val track: TrackState,
        private val key: CardKey,
        private val question: AiQuestion,
    ) {
        private var started: Instant? = null
        private var cost = QuestionCost()

        val counted: (QuestionCost) -> Unit = { more ->
            cost += more
            paid(more)
        }

        fun start() {
            note(work, PipelineEvent.AiAsked(track.normalized.lookupKey, question, false, key.provider, key.model))
            started = clock.now()
        }

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
    }

    // [key]'s answer to [question], kept, written at [writtenAt], served for [track] (#851): asked by a tap.
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

    // The kept reasons and lists told to the log as served, by key and track as the sources name it,
    // whatever its length (#851), the last
    // [TOLD_KEPT], under [lock].
    private val told =
        object : LinkedHashMap<Pair<CardKey, Any>, Unit>(TOLD_KEPT, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<CardKey, Any>, Unit>?): Boolean =
                size > TOLD_KEPT
        }

    // Tells [event], [key]'s kept reason or list served for [track], to the log once per track
    // (#851), whichever of its producers comes first (a tap's read, a panel's); under [lock], which
    // the caller holds, having checked that no *Clear history* came since its work started.
    private fun tellKept(
        track: TrackState,
        key: CardKey,
        event: PipelineEvent,
    ) {
        if (told.put(key to track.normalized, Unit) == null) events(event)
    }

    // Tells [event] to the log if no *Clear history* came since [work] started, checked under the
    // lock [forget] clears under, as [publish] shows (#851).
    private fun note(
        work: CoroutineContext,
        event: PipelineEvent,
    ) = synchronized(lock) { if (current(work)) events(event) }

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
        val asked = Asked(work, track, key, AiQuestion.GenreList)
        try {
            val kept = asker.kept(key)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                synchronized(lock) {
                    if (current(work)) tellKept(track, key, kept(track, key, AiQuestion.GenreList, kept.writtenAt))
                }
                show(kept.state, kept.writtenAt ?: clock.now())
            } else {
                asked.start()
                val end = asker.ask(key, track, asking(), asked.counted, show)
                asked.end(
                    if (end is GenreListState.Listed) AiOutcome.ANSWERED else AiOutcome.FAILED,
                    (end as? GenreListState.Failed)?.reason,
                )
            }
        } catch (e: CancellationException) {
            asked.end(AiOutcome.STOPPED)
            throw e
        } catch (
            // A bug, not the network, which the list maps to a state: shown as failed, offered again.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val reason = e::class.simpleName ?: FAILED
            asked.end(AiOutcome.FAILED, reason)
            show(GenreListState.Failed(reason), clock.now())
        } finally {
            writing.remove(key)
        }
        // Each genre asked for, on the list, written; none after a *Clear history* since the list
        // was asked, which drops their claims.
        if (current(work)) {
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
            forgets++
            shown.value = emptyMap()
            lists.value = emptyMap()
            waiting.value = emptyMap()
            looked.clear()
            told.clear()
        }
        lookups.forget()
    }

    // Guards each reason or list shown against *Clear history* ([forget]).
    private val lock = Any()

    // How many times [forget] ran, under [lock]. *Clear history* advances the epoch before it clears
    // the caches and only then calls [forget], so work started in between (a panel asking for a
    // reason meanwhile) carries the new epoch, and could read the cache before its clear and show
    // what it read after [forget] (#791). Work is marked with this count too ([mark]), and shows
    // nothing once it has changed.
    @Volatile
    private var forgets = 0L

    // The count of [forget]s a piece of work started after; its children inherit it.
    private class Forgets(
        val count: Long,
    ) : AbstractCoroutineContextElement(Forgets) {
        companion object Key : CoroutineContext.Key<Forgets>
    }

    // Marks work with the epoch and the count of [forget]s, when it starts.
    private fun mark(): CoroutineContext = epoch.mark() + Forgets(forgets)

    // Whether [work] started after the last *Clear history*: in the epoch now, and after the last
    // [forget]. Work this did not mark counts as current, as with the epoch.
    private fun current(work: CoroutineContext): Boolean =
        epoch.isCurrent(work) && work[Forgets]?.count.let { it == null || it == forgets }

    /** For tests: runs between a state's check and its showing ([publish]). */
    @Volatile
    internal var beforePublish: () -> Unit = {}

    // Runs [show], a reason or a list shown, if no *Clear history* came since [work] started
    // ([current]), checked under the lock [forget] clears under: so its clear, which comes after the
    // epoch's advance, either comes after the state shown, and clears it, or before the check, which
    // then fails. As `ShownSearches.publish`.
    private fun publish(
        work: CoroutineContext,
        show: () -> Unit,
    ) = synchronized(lock) {
        if (current(work)) {
            beforePublish()
            show()
        }
    }

    // [genre]'s reason kept in the synthesis cache for [key] (`GenreReasons.found`), read once,
    // unless one is shown already.
    private fun look(
        key: CardKey,
        track: TrackState,
        genre: String,
    ) {
        if (key in shown.value || !looked.add(key)) return
        val made = parts.value ?: return
        scope.launch(mark()) {
            val work = coroutineContext
            val kept = reasonsOf(made).found(key, track)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                val entry = Entry(kept.state, kept.writtenAt ?: clock.now())
                publish(work) {
                    var added = false
                    shown.update { (if (key in it) it else it.with(key, entry)).also { next -> added = next !== it } }
                    val question = AiQuestion.Reason(label(track, genre))
                    if (added) tellKept(track, key, kept(track, key, question, kept.writtenAt))
                }
            }
        }
    }

    // [track]'s AI genre list kept in the synthesis cache for [key] (#762), read once, unless one is known.
    private fun lookList(
        key: CardKey,
        track: TrackState,
    ) {
        if (key in lists.value || !looked.add(key)) return
        val made = parts.value ?: return
        scope.launch(mark()) {
            val work = coroutineContext
            val kept = listsOf(made).kept(key)?.takeIf { !expired(it.writtenAt) }
            if (kept != null) {
                val entry = Entry<GenreListState>(kept.state, kept.writtenAt ?: clock.now())
                publish(work) {
                    var added = false
                    lists.update { (if (key in it) it else it.with(key, entry)).also { next -> added = next !== it } }
                    if (added) tellKept(track, key, kept(track, key, AiQuestion.GenreList, kept.writtenAt))
                }
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

        /** The kept answers told to the log as served, remembered: those of the last tracks played. */
        const val TOLD_KEPT = 256

        const val LOAD = .75f
    }
}
