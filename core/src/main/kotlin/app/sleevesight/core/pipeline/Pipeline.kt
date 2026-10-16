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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    private val acted = MutableStateFlow(false)

    /** The diagnostics stream (`design/architecture.md`, *Diagnostics*). */
    val events: SharedFlow<PipelineEvent> = eventFlow.asSharedFlow()

    /** The current track's state, null before the first track. */
    val state: StateFlow<TrackState?> = stateFlow.asStateFlow()

    /** The user acted in the app (after it opened, nothing is written until they do; D5). */
    fun userActed() {
        acted.value = true
    }

    /**
     * The app opened (again): nothing is written without a tap until the user's next action in it
     * (D5), the tracks already under way included, since a run asks whether to write only once its
     * evidence is in.
     */
    fun appOpened() {
        acted.value = false
    }

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
        acted.value = true
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
     * finishes them, and keeps their results, but waits before MusicBrainz's genre labels and
     * before a model call. Whether to write without a tap is asked once the evidence is in and
     * the run may go on, so an action, or the app opening again ([appOpened]), meanwhile counts.
     */
    @OptIn(FlowPreview::class)
    suspend fun follow(
        changes: Flow<TrackChange?>,
        allowed: Flow<Boolean> = flowOf(true),
    ) {
        val gate: suspend () -> Unit = { allowed.first { it } }
        changes
            .filterNotNull()
            .onEach { if (it.cause == ChangeCause.USER_ACTION) acted.value = true }
            .debounce(DEBOUNCE)
            .map { change -> change to parts.normalizer.normalize(change.nowPlaying) }
            .distinctUntilChangedBy { (change, track) -> RunKey.of(track, change.nowPlaying) }
            .collectLatest { (change, track) ->
                gate()
                val current = settings()
                start(change.nowPlaying, track, current).run(
                    autoWrite = { AutoWrite.writes(current, change.cause, acted.value) },
                    waitForTap = true,
                    gate = gate,
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

    private fun MutableStateFlow<Long>.updateAndGetValue(): Long {
        while (true) {
            val seen = value
            if (compareAndSet(seen, seen + 1)) return seen + 1
        }
    }

    // Everything a run reads from a change: two changes with the same key give the same run. The
    // length in whole seconds, as the cache keys hold it, so a player's millisecond jitter is no change.
    private data class RunKey(
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
