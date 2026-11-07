package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.LastFmLabels
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.lyrics.LyricsLibrary
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ItemLanguageLines
import app.sleevesight.core.sources.LanguageSearch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SongWebLookup
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.text.DateForm
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
import kotlin.time.Duration.Companion.seconds
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
 * app passes the same one to each. [webSearch] is the web search engine *AI web search* asks with
 * (#801, [AiSearches]), on the user's key, or none: no engine switched on with its key. [albums]
 * gathers the evidence for an album's insight under *Key insights* (#522, [ItemInsights]), or none
 * (the CLI), when the album's insight has nothing to be written from. [modelUnavailable] when there
 * is no [ai] because the selected service's model is found unavailable (#540): nothing is written,
 * but the key insight kept is still read and shown (#911, `InsightWriter`). [artistWeb] searches the
 * web for an artist the sources say nothing about, and keeps what it found (#912, [ItemInsights]),
 * on the user's web search key, or none (no engine switched on with its key, the CLI). [songWeb]
 * likewise for a song the sources only list (#958).
 * [languageSearch] looks the track up in the language it is sung in, when not English (#902,
 * [LanguageSearch]), or none (no such search). [languageLines] keeps what that search found about
 * the artist and the album, per artist and per album, for their insights on their other tracks
 * (#939, [ItemInsights]), or none (the CLI), when they take only what their track's own search found.
 * [dateForm] gives the form the prompts ask for dates in (#957, `InsightPrompt.dateRule`), read as
 * each prompt is built: in the app, the phone's own form in English, as *Dates* writes them;
 * elsewhere the app's ([DateForm.APP]). [lyricsLibrary] is LRCLIB (#997), asked whether the track has
 * lyrics when one of its genres is Instrumental ([LibrarySign]), or none (no such sign).
 */
@Suppress("LongParameterList") // Independent stages, and why one is missing; all but the first four are optional.
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
    val webSearch: WebSearcher? = null,
    val albums: AlbumLookup? = null,
    val modelUnavailable: Boolean = false,
    val artistWeb: ArtistWebLookup? = null,
    val languageSearch: LanguageSearch? = null,
    val dateForm: () -> DateForm = { DateForm.APP },
    val languageLines: ItemLanguageLines? = null,
    val songWeb: SongWebLookup? = null,
    val lyricsLibrary: LyricsLibrary? = null,
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
        // A run that asks again (a *Retry*, back online, *Look again*) starts with the genres shown
        // for the same track (#772), so they stay up while it asks; a new track starts with none.
        val genresBefore =
            shown.state.value
                ?.takeIf { (again != null || lookAgain != null) && it.normalized.lookupKey == track.lookupKey }
                ?.genres ?: TrackGenres()
        // A *Retry* keeps what the run before's label requests gave for the sources it does not ask
        // again (#772).
        val laterKept =
            shown.state.value
                ?.takeIf { again?.only != null && it.normalized.lookupKey == track.lookupKey }
                ?.let { TrackLabels.LaterKept(it.laterFound, it.laterLabels, it.laterAnswered, it.libraryUnreachable) }
        return TrackRun(
            parts = parts,
            time = time,
            input = RunInput(current, nowPlaying, track, again, lookAgain, LabelsBefore(genresBefore, laterKept)),
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

        /**
         * How long a run's lookups may run in all, its sources' and the search in the track's language
         * together, before those still under way are cut and land unreachable ([GatherBudget], #983): a
         * gather takes about 2 s, and one stalled request, with its timeouts and retries, could hold the
         * card for minutes.
         */
        val GATHER_DEADLINE: Duration = 20.seconds
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
