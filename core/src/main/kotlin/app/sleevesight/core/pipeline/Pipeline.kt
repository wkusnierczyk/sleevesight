package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.LastFmLabels
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
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
 * [Resolution.unreachable].
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
 * it (the CLI), MusicBrainz gives none. [lastFmLabels] gives Last.fm's for the track and its album
 * (#439), likewise, made on the user's Last.fm key; without it, Last.fm gives its artists' only.
 * [sessionFacts] holds the artist facts the listening session's insights cited (#239), which a
 * later track's prompt leaves out (`PromptEvidence`); it outlives a rebuild of the parts, so the
 * app passes the same one to each.
 */
@Suppress("LongParameterList") // Nine independent stages; the last five are optional.
class PipelineParts(
    val normalizer: Normalizer,
    val adapters: List<SourceAdapter>,
    val resolver: TrackResolver,
    val hasher: Hasher,
    val ai: AiAccess? = null,
    val cache: PipelineCache = PipelineCache.None,
    val musicBrainzLabels: MusicBrainzLabels? = null,
    val lastFmLabels: LastFmLabels? = null,
    val sessionFacts: SessionFacts = SessionFacts.None,
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
 * resolved ids land (web search, with no id to verify against, only filters its results); publish
 * a [TrackState] as each source lands; assemble the evidence; then write the insight, on request
 * or as the auto-write setting says (D5), from the synthesis cache when it has it, and check it.
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

    // What [state] shows, with *Clear history*'s count (#547): a run started before the last
    // [forget] publishes nothing, and emits no event, after it.
    private val shown = ShownState()
    private val runs = MutableStateFlow(0L)
    private val taps = MutableStateFlow(0L)
    private val acted = MutableStateFlow(false)

    /** The diagnostics stream (`design/architecture.md`, *Diagnostics*). */
    val events: SharedFlow<PipelineEvent> = eventFlow.asSharedFlow()

    /** The current track's state, null before the first track. */
    val state: StateFlow<TrackState?> = shown.state

    /**
     * Forgets the current track's state, for *Clear history* (#547): [state] is null again, and no
     * run or preparation started before publishes a state or an event after it.
     */
    fun forget() = shown.forget()

    /** The user acted in the app (after it started, nothing is written until they do; D5). */
    fun userActed() {
        acted.value = true
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
     */
    @OptIn(FlowPreview::class)
    suspend fun follow(changes: Flow<TrackChange?>) {
        changes
            .filterNotNull()
            .onEach { if (it.cause == ChangeCause.USER_ACTION) acted.value = true }
            .debounce(DEBOUNCE)
            .map { change -> change to parts.normalizer.normalize(change.nowPlaying) }
            .distinctUntilChangedBy { (change, track) ->
                RunKey.of(track, change.nowPlaying)
            }.collectLatest { (change, track) ->
                val current = settings()
                val writes = AutoWrite.writes(current, change.cause, acted.value)
                start(change.nowPlaying, track, current, Followed(writes, taps)).run()
            }
    }

    /**
     * Runs the pipeline for [nowPlaying] once, each step that sends requests or spends starting
     * only once [control] grants it, and returns its final state: the app's loop, whose
     * coordinator decides when (#51). The state is published as for [follow].
     *
     * With [again], what an earlier run of the same track gathered, it asks again only the
     * sources that were unreachable then (back online, #192): the others are kept as they landed,
     * and a resolution made then is used again, not asked for. With [lookAgain] (#617), it looks
     * every source up afresh, past the caches, keeping what the run before found of a source it
     * cannot reach now ([LookAgain]).
     */
    suspend fun run(
        nowPlaying: NowPlaying,
        control: RunControl,
        again: Gathered? = null,
        lookAgain: LookAgain? = null,
    ): TrackState =
        start(nowPlaying, parts.normalizer.normalize(nowPlaying), settings(), control, again, lookAgain).run()

    /**
     * Prepares [next], the next item in the player's queue, ahead of its play (D13, *Evidence*):
     * its resolution by name and what the playing track's run then finds by name or id
     * ([Preparation]). Publishes nothing, keeps nothing as the track's own, and writes no insight;
     * each step that sends requests starts once [control] grants it.
     */
    suspend fun prepare(
        next: NowPlaying,
        control: RunControl,
    ) {
        val since = shown.forgets
        Preparation(parts, time, settings(), parts.normalizer.normalize(next), control) {
            if (shown.forgets == since) eventFlow.tryEmit(it)
        }.run()
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
        return start(nowPlaying, track, settings(), RunControl.Unattended(write)).run()
    }

    // The track, its settings and control, and how it looks up: as asked again, or afresh.
    @Suppress("LongParameterList")
    private fun start(
        nowPlaying: NowPlaying,
        track: NormalizedTrack,
        current: Settings,
        control: RunControl,
        again: Gathered? = null,
        lookAgain: LookAgain? = null,
    ): TrackRun {
        val token = runs.updateAndGetValue()
        val since = shown.forgets
        return TrackRun(
            parts = parts,
            time = time,
            input = RunInput(current, nowPlaying, track, again, lookAgain),
            control = control,
            onEvent = { if (shown.forgets == since) eventFlow.tryEmit(it) },
            publish = { state -> shown.publish(state, since) { runs.value == token } },
        )
    }

    private fun MutableStateFlow<Long>.updateAndGetValue(): Long {
        while (true) {
            val seen = value
            if (compareAndSet(seen, seen + 1)) return seen + 1
        }
    }

    companion object {
        /** How long a track change waits for the next before it runs: skips settle first. */
        val DEBOUNCE: Duration = 650.milliseconds

        /** Events kept for a slow collector before the oldest are dropped. */
        const val EVENT_BUFFER: Int = 256
    }
}

/**
 * Everything a run reads from a report: two reports with the same key give the same run (the
 * normalized track, its credited artists and work included, the album and the length). The length
 * in whole seconds, as the cache keys hold it, so a player's millisecond jitter is no change.
 */
data class RunKey(
    val track: NormalizedTrack,
    val album: String?,
    val seconds: Long?,
) {
    companion object {
        fun of(
            track: NormalizedTrack,
            nowPlaying: NowPlaying,
        ): RunKey = RunKey(track, nowPlaying.album, nowPlaying.duration?.inWholeSeconds)

        /** The key of [nowPlaying], normalized by [normalizer]. */
        fun of(
            normalizer: Normalizer,
            nowPlaying: NowPlaying,
        ): RunKey = of(normalizer.normalize(nowPlaying), nowPlaying)
    }
}
