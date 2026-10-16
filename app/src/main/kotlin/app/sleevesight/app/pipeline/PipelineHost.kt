package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.FirstAction
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.Pipeline
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackChange
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the home screen (#52) and the diagnostics screen (#54) read of the pipeline.
 *
 * @property track the current track's state, null before the first run. It stays through a
 *   rebuild of the pipeline (a key saved, say) until the new pipeline publishes its own, and
 *   offers no Write while it is not the track the session layer shows or the pipeline's.
 * @property needsKey the keyed sources (Last.fm, web search) switched on under *Where to look* (web
 *   search with an engine switched on, `Settings.isEnabled`) that are not looked up because their
 *   key is not saved, or cannot be read: their state is that they
 *   need a key, not a failure. They have no entry in [TrackState.sources], which holds one per
 *   adapter, and a source needs its key for an adapter.
 * @property aiReady whether an insight can be written: an AI service is chosen, it has a client,
 *   and its key is saved and readable.
 */
data class PipelineView(
    val track: TrackState? = null,
    val needsKey: Set<SourceId> = emptySet(),
    val aiReady: Boolean = false,
)

/**
 * The keys a pipeline is built with: the chosen AI service's, and the keyed sources'. Not a data
 * class, on purpose: no generated `toString`, `equals` or `copy` handles the [Secret]s.
 */
class PipelineKeys(
    val ai: Secret? = null,
    val sources: SourceKeys = SourceKeys(),
)

/**
 * The shared pipeline ([Pipeline]) run in the app, under the app's rules (#51; architecture,
 * *How the app runs it*, D5, D6):
 *
 * - **Nothing before the first run.** Until [acknowledged] is true it reads no session, no setting
 *   and no key, and builds nothing, so nothing is sent: notification access can be granted before
 *   the app is first opened (#48).
 * - **Fed from the session layer.** Each track the session layer shows ([nowPlaying]) goes to
 *   [Pipeline.follow], which waits [Pipeline.DEBOUNCE] for the next and runs only the latest,
 *   cancelling a superseded track's run. The change's cause is the default, "ended on its own or
 *   could not tell": skip detection, and the actions in Sleevesight, are #53's.
 * - **Requests only while visible** (D6): while no activity is started ([activityStarted],
 *   [activityStopped]) and *Look up in the background* is off ([lookupsAllowed]), no run starts
 *   its lookups. A run whose lookups are under way finishes them, their follow-up requests
 *   included, and keeps what they found, but waits before MusicBrainz's labels and before a model
 *   call. When requests are allowed again the run waiting goes on if it is still current: the
 *   visible track catches up, whether or not it is the one last run, and the tracks played
 *   meanwhile are never looked up. A run is current while the latest report is of its track
 *   ([Pipeline.follow], *Currency*): one superseded waits, and decides and starts nothing.
 * - **The app opening.** The first activity started while none was is the app opening; a restart
 *   for a configuration change (a rotation) is not. It clears the first action ([FirstAction],
 *   one for the app, shared by every pipeline built), so nothing is written without a tap until
 *   the user's next action (D5), the run waiting included, since it decides only once it may go
 *   on.
 * - **Write on tap**: [write] writes the current track's insight once its evidence is in, and is
 *   the user's first action. The state offers Write only from the pipeline that takes taps, for
 *   the track shown: not while a newer report waits for its run, nor once a rebuild has begun.
 * - **Built from the keys.** [parts] builds the pipeline's stages from the keys (read with
 *   [readKeys]) and the settings. The adapter list depends on the keyed sources' keys and the web
 *   engines switched on, and the AI access on the service chosen and its key, so a change to any
 *   of them ([keyChanges], or those settings) builds a new pipeline; other settings are read by the
 *   pipeline for each track, and change nothing here. What an adapter keeps in memory (Last.fm's
 *   verdicts) is lost with it, which is why nothing else rebuilds it. A rebuild never spends twice
 *   on a track: the old run is made stale at once ([Pipeline.supersede]), a model call under way
 *   is let finish (and its card is kept), and when the old run had already decided its insight
 *   the new pipeline runs the track shown again as a [ChangeCause.REPLAY], which is not a track
 *   change, so it shows the card kept for it or offers Write, and writes nothing without a tap; a
 *   run cut off before it decided keeps its cause, so it is decided once. The first action is
 *   one for the app ([FirstAction]), shared by every pipeline built. No pipeline is built until
 *   the keys' first read succeeds (`SecretStore.changes` retries a failed one).
 *
 * [state] is what the UI renders; [events] the diagnostics stream, across rebuilds, which never
 * blocks the pipeline. A pipeline that fails, or cannot be built (a bug: the stages map their
 * failures to outcomes), is reported to [onFailure] and stays stopped until the next rebuild.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The inputs of one loop, each a seam for the tests.
class PipelineHost(
    private val acknowledged: Flow<Boolean>,
    private val nowPlaying: Flow<NowPlaying?>,
    private val settings: Flow<Settings>,
    private val keyChanges: Flow<Unit>,
    private val readKeys: suspend (ProviderId?) -> PipelineKeys,
    private val parts: (PipelineKeys, Settings) -> PipelineParts,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val visibleState = MutableStateFlow(false)
    private val firstAction = FirstAction()

    // The activities started and not stopped, and whether the last stop was for a configuration
    // change, after which the next start is not an opening. Main thread only.
    private var started = 0
    private var restarting = false
    private val view = MutableStateFlow(PipelineView())
    private val eventFlow =
        MutableSharedFlow<PipelineEvent>(
            extraBufferCapacity = Pipeline.EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // The pipeline that takes taps; null while none is built, or one is being rebuilt.
    private val current = MutableStateFlow<Pipeline?>(null)

    // The track the session layer shows, once the host has started.
    @Volatile
    private var playing: StateFlow<NowPlaying?>? = null

    /** What the UI renders. */
    val state: StateFlow<PipelineView> = view.asStateFlow()

    /** The diagnostics stream of every pipeline built, in order; a slow collector loses the oldest. */
    val events: SharedFlow<PipelineEvent> = eventFlow.asSharedFlow()

    /**
     * Whether requests may start (D6): the app is visible, or *Look up in the background* is on. For
     * the pipeline, and for the session layer's `http` art.
     */
    val lookupsAllowed: Flow<Boolean> =
        combine(visibleState, settings) { shown, now -> shown || now.lookUpInBackground }.distinctUntilChanged()

    /**
     * An activity started (`MainActivity.onStart`, on the main thread). The first one while none is
     * is the app opening, unless the last stop was for a configuration change.
     */
    fun activityStarted() {
        if (started == 0 && !restarting) firstAction.opened()
        restarting = false
        started++
        visibleState.value = true
    }

    /**
     * An activity stopped (`MainActivity.onStop`). The app is visible while any activity is
     * started, and through a restart for a configuration change ([changingConfigurations]), whose
     * new activity starts at once.
     */
    fun activityStopped(changingConfigurations: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started > 0) return
        restarting = changingConfigurations
        if (!changingConfigurations) visibleState.value = false
    }

    /**
     * The user tapped Write: the current track's insight is written once its evidence is in. A tap
     * that arrives while the pipeline's track is not the one shown (the state then offers no Write)
     * writes nothing: it would pay for a track about to be superseded.
     */
    fun write() {
        val pipeline = current.value ?: return
        val shown = playing?.value
        // [Pipeline.write] counts it as the first action; a tap that writes nothing does not.
        if (shown == null || pipeline.isCurrent(shown)) pipeline.write()
    }

    /** Runs until [scope] is cancelled; called once. */
    fun start(scope: CoroutineScope) {
        scope.launch { run() }
    }

    private suspend fun run() =
        coroutineScope {
            acknowledged.first { it }
            val latest = settings.stateIn(this)
            val shown = nowPlaying.stateIn(this)
            playing = shown
            val allowed = lookupsAllowed.stateIn(this)
            val inputs = latest.map { BuildInput(it.webEngines, it.aiProvider) }.distinctUntilChanged()
            var running: Job? = null
            var live: Pipeline? = null
            combine(keyChanges, inputs) { _, input -> input }
                .collectLatest { input ->
                    // No tap may go to the pipeline being replaced, nor be lost with it.
                    current.value = null
                    view.update { it.copy(track = it.track?.let(::withoutWrite)) }
                    val old = live
                    // Stale from now on: it starts and decides nothing more.
                    val decided = old?.supersede() ?: false
                    // A model call under way is paid for: let it finish, and its card be kept.
                    old?.state?.first { it?.insight !is InsightState.Writing }
                    running?.cancelAndJoin()
                    val replayOf = old?.takeIf { decided }?.let { it::isCurrent }
                    val reads = RunInputs(latest, shown, allowed, replayOf)
                    running = launch { follow(input, reads) { live = it } }
                }
        }

    private suspend fun follow(
        input: BuildInput,
        inputs: RunInputs,
        onBuilt: (Pipeline?) -> Unit,
    ) = coroutineScope {
        try {
            val built = parts(readKeys(input.provider), inputs.latest.value)
            val pipeline = Pipeline(built, firstAction = firstAction) { inputs.latest.value }
            onBuilt(pipeline)
            launch(start = CoroutineStart.UNDISPATCHED) { pipeline.events.collect { eventFlow.tryEmit(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { publish(pipeline, built, inputs) }
            current.value = pipeline
            try {
                pipeline.follow(changes(inputs), inputs.allowed)
            } finally {
                current.compareAndSet(pipeline, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Kept stopped rather than restarted, which could repeat the failing run's requests;
            // nothing of it is under way, so a rebuild waits for nothing and replays nothing.
            onBuilt(null)
            onFailure(e)
            awaitCancellation()
        }
    }

    // The view of [pipeline]'s state. Until it publishes, the previous pipeline's track stays,
    // without Write; so does a track the session layer has moved on from, until its run starts,
    // and every track while [pipeline] is not the one that takes taps (a rebuild has begun).
    private suspend fun publish(
        pipeline: Pipeline,
        built: PipelineParts,
        inputs: RunInputs,
    ) {
        val adapters = built.adapters.map { it.id }.toSet()
        combine(pipeline.state, inputs.latest, inputs.shown, current) { track, now, playing, taking ->
            val shownTrack =
                when {
                    track == null -> view.value.track?.let(::withoutWrite)
                    // Write only from the pipeline that takes taps, for the track shown.
                    taking !== pipeline -> withoutWrite(track)
                    playing != null && !pipeline.isCurrent(playing) -> withoutWrite(track)
                    else -> track
                }
            PipelineView(
                track = shownTrack,
                needsKey = KEYED_SOURCES.filter { now.isEnabled(it) && it !in adapters }.toSet(),
                aiReady = built.ai != null,
            )
        }.collect { view.value = it }
    }

    private fun withoutWrite(track: TrackState): TrackState =
        if (track.insight is InsightState.Idle) track.copy(insight = InsightState.Idle(canWrite = false)) else track

    // Each report of the session layer, as a change; the track a rebuilt pipeline starts on, if it
    // is the one the previous pipeline was about, as a replay.
    private fun changes(inputs: RunInputs): Flow<TrackChange?> {
        var replaying = inputs.replayOf != null
        return inputs.shown.map { playing ->
            playing?.let {
                replaying = replaying && inputs.replayOf?.invoke(it) == true
                TrackChange(it, if (replaying) ChangeCause.REPLAY else ChangeCause.NATURAL_END)
            }
        }
    }

    // What the pipeline is built from, besides the keys.
    private data class BuildInput(
        val engines: Set<WebEngine>,
        val provider: ProviderId?,
    )

    // What each pipeline built reads.
    private class RunInputs(
        val latest: StateFlow<Settings>,
        val shown: StateFlow<NowPlaying?>,
        val allowed: StateFlow<Boolean>,
        val replayOf: ((NowPlaying) -> Boolean)?,
    )

    companion object {
        /** The sources looked up only with the user's key (design, *Source adapters*). */
        val KEYED_SOURCES: Set<SourceId> = setOf(SourceId.LASTFM, SourceId.WEB)
    }
}
