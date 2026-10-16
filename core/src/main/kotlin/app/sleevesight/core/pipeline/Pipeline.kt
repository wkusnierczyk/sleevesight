package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Resolution, steps 1 to 4 (`design/architecture.md`, *Entity resolution*): `RecordingResolver` over
 * MusicBrainz in the app and the CLI, a fake in tests. It never throws for a failed lookup, which is
 * [Resolution.UNREACHABLE].
 */
fun interface TrackResolver {
    suspend fun resolve(
        track: NormalizedTrack,
        duration: Duration?,
    ): Resolution
}

/** The AI service the user chose, and their key for it. */
class AiAccess(
    val provider: AiProvider,
    val key: Secret,
)

/**
 * What the pipeline is built from: the stages it joins. [adapters] holds one adapter per source.
 * [musicBrainzLabels] gives MusicBrainz's genre labels (#84), asked for after the evidence; without
 * it (the CLI), MusicBrainz gives none.
 */
@Suppress("LongParameterList") // Seven independent stages; the last three are optional.
class PipelineParts(
    val normalizer: Normalizer,
    val adapters: List<SourceAdapter>,
    val resolver: TrackResolver,
    val hasher: Hasher,
    val ai: AiAccess? = null,
    val cache: PipelineCache = PipelineCache.None,
    val musicBrainzLabels: MusicBrainzLabels? = null,
) {
    init {
        require(adapters.map { it.id }.distinct().size == adapters.size) { "One adapter per source" }
    }
}

/**
 * Whether the user has acted in the app since it last opened (D5): after it opens, nothing is
 * written without a tap until they do. One for the app, shared by every pipeline it builds, so a
 * pipeline built anew neither copies it nor misses an opening; each run reads it when it decides.
 */
class FirstAction {
    private val state = MutableStateFlow(false)

    /** Whether the user has acted since the app last opened. */
    val made: Boolean get() = state.value

    /** The user acted in the app. */
    fun acted() {
        state.value = true
    }

    /** The app opened (again): no action yet. */
    fun opened() {
        state.value = false
    }
}

/** Where the pipeline's time comes from: [clock] for retrieval times, [timeSource] for latencies. */
class PipelineTime(
    val clock: Clock = Clock.System,
    val timeSource: TimeSource = TimeSource.Monotonic,
)

/**
 * The pipeline the CLI and the app run (`design/architecture.md`, *Data flow*, *Concurrency*,
 * *State*): normalize; consult the retrieval cache; look every missing source up by name in
 * parallel, with MusicBrainz resolution alongside (D4), each adapter verifying its match when the
 * resolved ids land; publish a [TrackState] as each source lands; assemble the evidence; then
 * write the insight, on request or as the auto-write setting says (D5), from the synthesis cache
 * when it has it, and check it.
 *
 * [follow] is the app's loop over track changes, which debounces them and cancels a superseded
 * track's run (`collectLatest`); [run] is one track, as the CLI uses it. [state] is the current
 * track's state; [events] the diagnostics stream, which never blocks the pipeline: a slow collector
 * loses the oldest events.
 *
 * [settings] is read once per track, so a track runs under one set of settings.
 *
 * The `Deferred<Resolution>` the adapters await is always completed normally: with
 * [Resolution.NONE] when MusicBrainz is off, or resolution throws or is cancelled. A result is kept
 * in the retrieval cache only when the resolution was made: one verified against a resolution that
 * could not be made (MusicBrainz unreachable, or off) would stay unverified once it is back.
 */
class Pipeline(
    private val parts: PipelineParts,
    private val time: PipelineTime = PipelineTime(),
    private val firstAction: FirstAction = FirstAction(),
    private val settings: () -> Settings,
) {
    private val eventFlow =
        MutableSharedFlow<PipelineEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val stateFlow = MutableStateFlow<TrackState?>(null)
    private val runs = MutableStateFlow(0L)
    private val taps = MutableStateFlow(0L)

    private val tickets = RunTickets()

    /** The diagnostics stream (`design/architecture.md`, *Diagnostics*). */
    val events: SharedFlow<PipelineEvent> = eventFlow.asSharedFlow()

    /** The current track's state, null before the first track. */
    val state: StateFlow<TrackState?> = stateFlow.asStateFlow()

    /** The user acted in the app (after it opened, nothing is written until they do; D5). */
    fun userActed() = firstAction.acted()

    /**
     * The app opened (again): nothing is written without a tap until the user's next action in it
     * (D5), the tracks already under way included, since a run asks whether to write only once its
     * evidence is in.
     */
    fun appOpened() = firstAction.opened()

    /**
     * Makes the run in progress stale (in the app: this pipeline is being replaced): it starts no
     * request, and decides nothing, from now on; a model call already under way goes on. True when
     * the run had already decided its insight (written it, or is writing it, or served it from the
     * cache, or chose to wait for a tap): a replacement's run of the same track is then a
     * [ChangeCause.REPLAY], and not otherwise, so a run cut off before it decided is still decided.
     */
    fun supersede(): Boolean = tickets.supersede()

    /**
     * Whether [nowPlaying] is the track [state] is about, in everything a run depends on: a report
     * that is not would start a run of its own, so the state shown is not yet its.
     */
    fun isCurrent(nowPlaying: NowPlaying): Boolean {
        val shown = state.value ?: return false
        val key = RunKey.of(parts.normalizer.normalize(nowPlaying), nowPlaying)
        return key == RunKey.of(shown.normalized, shown.nowPlaying)
    }

    /** The user tapped Write: the current track's insight is written once its evidence is in. */
    fun write() {
        firstAction.acted()
        taps.update { it + 1 }
    }

    /**
     * Runs the pipeline over [changes] until cancelled: a `null` (no active session) is ignored, a
     * change waits [DEBOUNCE] for the next to settle, and one identical to the last in everything a
     * run depends on (the normalized track, its credited artists and work included, the album and
     * the length) is not run again; a metadata update that changes none of them is suppressed. A
     * new track cancels the previous one's retrieval and synthesis.
     *
     * While [allowed] is false (in the app: not visible, and *Look up in the background* off; D6)
     * no request starts: a run waits before its lookups, and a run whose lookups are under way
     * finishes them (their follow-up requests included) and keeps their results, but waits before
     * MusicBrainz's genre labels and before a model call. Whether to write without a tap is asked
     * once the evidence is in and the run may go on, so an action, or the app opening again
     * ([appOpened]), meanwhile counts.
     *
     * **Currency.** A report of another track makes the run in progress stale at once, before its
     * debounce: a stale run starts no request after a gate and decides nothing, but waits, so
     * neither a newer track's action nor a gate opening during the newer track's debounce
     * authorises anything for it; the debounce only delays starting the replacement, which
     * cancels it. A report of its own track again before then (a skip there and back) makes it
     * current again, and it goes on.
     */
    @OptIn(FlowPreview::class)
    suspend fun follow(
        changes: Flow<TrackChange?>,
        allowed: Flow<Boolean> = flowOf(true),
    ) {
        val gate: suspend () -> Unit = { allowed.first { it } }
        changes
            .filterNotNull()
            .map { change ->
                val track = parts.normalizer.normalize(change.nowPlaying)
                val report = Report(change, track, RunKey.of(track, change.nowPlaying))
                // Stale before the action is counted, so the run cannot decide on it.
                tickets.reported(report.key)
                if (change.cause == ChangeCause.USER_ACTION) firstAction.acted()
                report
            }.debounce(DEBOUNCE)
            .filter { report -> !tickets.isRun(report.key) }
            .collectLatest { report ->
                val currency = tickets.start(report.key)
                gate()
                currency.awaitCurrent()
                val current = settings()
                start(report.change.nowPlaying, report.track, current).run(
                    autoWrite = { AutoWrite.writes(current, report.change.cause, firstAction.made) },
                    waitForTap = true,
                    gate = gate,
                    currency = currency,
                )
            }
    }

    /**
     * Runs the pipeline for [nowPlaying] once and returns its final state: with the insight written
     * when [write] (and insights are on, with an AI service set up), else with its evidence only.
     */
    suspend fun run(
        nowPlaying: NowPlaying,
        write: Boolean,
    ): TrackState {
        val track = parts.normalizer.normalize(nowPlaying)
        return start(nowPlaying, track, settings()).run(autoWrite = { write }, waitForTap = false)
    }

    private fun start(
        nowPlaying: NowPlaying,
        track: NormalizedTrack,
        current: Settings,
    ): TrackRun {
        val token = runs.updateAndGetValue()
        return TrackRun(
            parts = parts,
            time = time,
            input = RunInput(current, nowPlaying, track),
            taps = taps,
            onEvent = { eventFlow.tryEmit(it) },
            publish = { state -> if (runs.value == token) stateFlow.value = state },
        )
    }

    // A report on its way to a run.
    private data class Report(
        val change: TrackChange,
        val track: NormalizedTrack,
        val key: RunKey,
    )

    // Everything a run reads from a change: two changes with the same key give the same run. The
    // length in whole seconds, as the cache keys hold it, so a player's millisecond jitter is no change.
    internal data class RunKey(
        val track: NormalizedTrack,
        val album: String?,
        val seconds: Long?,
    ) {
        companion object {
            fun of(
                track: NormalizedTrack,
                nowPlaying: NowPlaying,
            ) = RunKey(track, nowPlaying.album, nowPlaying.duration?.inWholeSeconds)
        }
    }

    companion object {
        /** How long a track change waits for the next before it runs: skips settle first. */
        val DEBOUNCE: Duration = 650.milliseconds

        /** Events kept for a slow collector before the oldest are dropped. */
        const val EVENT_BUFFER: Int = 256
    }
}

private fun MutableStateFlow<Long>.updateAndGetValue(): Long {
    while (true) {
        val seen = value
        if (compareAndSet(seen, seen + 1)) return seen + 1
    }
}

/**
 * Which of a pipeline's runs is current ([Pipeline.follow], *Currency*): the run last started,
 * while the latest report is of its track and the pipeline is not being replaced.
 */
private class RunTickets {
    // The run last started ([id], about [run]), whether it has decided its insight, the key of
    // the latest report, and whether the pipeline is being replaced.
    private data class Ticket(
        val id: Long = 0,
        val run: Pipeline.RunKey? = null,
        val latest: Pipeline.RunKey? = null,
        val decided: Boolean = false,
        val superseded: Boolean = false,
    )

    private val ticket = MutableStateFlow(Ticket())

    /** A report of [key] arrived: a run about another track is stale from now on. */
    fun reported(key: Pipeline.RunKey) = ticket.update { it.copy(latest = key) }

    /** Whether the run last started is about [key]. */
    fun isRun(key: Pipeline.RunKey): Boolean = ticket.value.run == key

    /** Makes the run in progress stale for good; whether it had decided its insight. */
    fun supersede(): Boolean {
        while (true) {
            val seen = ticket.value
            if (ticket.compareAndSet(seen, seen.copy(superseded = true))) return seen.decided
        }
    }

    /** Starts a run about [key], and returns its currency. */
    fun start(key: Pipeline.RunKey): RunCurrency {
        var id: Long
        while (true) {
            val seen = ticket.value
            id = seen.id + 1
            if (ticket.compareAndSet(seen, seen.copy(id = id, run = key, decided = false))) break
        }
        return currencyOf(id, key)
    }

    private fun currencyOf(
        id: Long,
        key: Pipeline.RunKey,
    ): RunCurrency =
        object : RunCurrency {
            private fun Ticket.isMine() = this.id == id && latest == key && !superseded

            override suspend fun awaitCurrent() {
                ticket.first { it.isMine() }
            }

            override suspend fun decide() {
                while (true) {
                    awaitCurrent()
                    val seen = ticket.value
                    if (seen.isMine() && (seen.decided || ticket.compareAndSet(seen, seen.copy(decided = true)))) return
                }
            }
        }
}
