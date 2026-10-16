package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the home screen (#52) and the diagnostics screen (#54) read of the pipeline.
 *
 * @property track the current track's state, null before the first run. It stays through a
 *   rebuild of the pipeline (a key saved, say) until the new pipeline publishes its own, with no
 *   Write to offer meanwhile.
 * @property needsKey the keyed sources (Last.fm, web search) switched on under *Where to look* that
 *   are not looked up because their key is not saved, or cannot be read: their state is that they
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

/** The keys a pipeline is built with: the chosen AI service's, and the keyed sources'. */
class PipelineKeys(
    val ai: Secret? = null,
    val sources: SourceKeys = SourceKeys(),
)

/**
 * The shared pipeline ([Pipeline]) run in the app, under the app's rules (#51; architecture,
 * *Concurrency*, D6):
 *
 * - **Nothing before the first run.** Until [acknowledged] is true it reads no session, no setting
 *   and no key, and builds nothing, so nothing is sent: notification access can be granted before
 *   the app is first opened (#48).
 * - **Fed from the session layer.** Each track the session layer shows ([nowPlaying]) goes to
 *   [Pipeline.follow], which waits [Pipeline.DEBOUNCE] for the next and runs only the latest,
 *   cancelling a superseded track's run. The change's cause is the default, "ended on its own or
 *   could not tell": skip detection, and the actions in Sleevesight, are #53's.
 * - **Only while visible** (D6): while the app is not visible ([visible]) and *Look up in the
 *   background* is off, no track reaches the pipeline, so no lookup and no model call starts. When
 *   the app is visible again, or the setting is turned on, the track shown then reaches it, after
 *   the debounce, and runs unless it is the track the pipeline last ran: the visible track
 *   catches up, and the tracks played meanwhile are never looked up. A run that began while the
 *   app was visible, a change seen then included, is not cancelled when it is hidden: what it has
 *   sent is paid for either way, and its results are cached for the track's return.
 * - **Write on tap**: [write] writes the current track's insight once its evidence is in (and
 *   counts as the user's first action, D5).
 * - **Built from the keys.** [parts] builds the pipeline's stages from the keys (read with
 *   [readKeys]) and the settings. The adapter list depends on the keyed sources' keys and the web
 *   engines switched on, and the AI access on the service chosen and its key, so a change to any
 *   of them ([keyChanges], or those settings) builds a new pipeline, which runs the track shown
 *   again; other settings are read by the pipeline for each track, and change nothing here. The
 *   user's first action carries over to the new pipeline. What an adapter keeps in memory (Last.fm's
 *   verdicts) is lost with it, which is why nothing else rebuilds it.
 *
 * [state] is what the UI renders; [events] the diagnostics stream, across rebuilds, which never
 * blocks the pipeline. A pipeline that fails (a bug: the stages map their failures to outcomes) is
 * reported to [onFailure] and stays stopped until the next rebuild.
 */
@Suppress("LongParameterList") // The inputs of one loop, each a seam for the tests.
class PipelineHost(
    private val acknowledged: Flow<Boolean>,
    private val nowPlaying: Flow<NowPlaying?>,
    private val settings: Flow<Settings>,
    private val keyChanges: Flow<Unit>,
    private val readKeys: suspend (ProviderId?) -> PipelineKeys,
    private val parts: (PipelineKeys, Settings) -> PipelineParts,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val visible = MutableStateFlow(false)
    private val view = MutableStateFlow(PipelineView())
    private val eventFlow =
        MutableSharedFlow<PipelineEvent>(
            extraBufferCapacity = Pipeline.EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val current = MutableStateFlow<Pipeline?>(null)

    @Volatile
    private var acted = false

    /** What the UI renders. */
    val state: StateFlow<PipelineView> = view.asStateFlow()

    /** The diagnostics stream of every pipeline built, in order; a slow collector loses the oldest. */
    val events: SharedFlow<PipelineEvent> = eventFlow.asSharedFlow()

    /** Whether the app is on screen: `MainActivity` sets it when it starts and stops. */
    fun visible(shown: Boolean) {
        visible.value = shown
    }

    /** The user tapped Write: the current track's insight is written once its evidence is in. */
    fun write() {
        acted = true
        current.value?.write()
    }

    /** Runs until [scope] is cancelled; called once. */
    fun start(scope: CoroutineScope) {
        scope.launch { run() }
    }

    private suspend fun run() =
        coroutineScope {
            acknowledged.first { it }
            val latest = settings.stateIn(this)
            val inputs = latest.map { BuildInput(it.webEngines, it.aiProvider) }.distinctUntilChanged()
            combine(keyChanges, inputs) { _, input -> input }
                .collectLatest { input -> follow(readKeys(input.provider), latest) }
        }

    private suspend fun follow(
        keys: PipelineKeys,
        latest: StateFlow<Settings>,
    ) = coroutineScope {
        val built = parts(keys, latest.value)
        val pipeline = Pipeline(built) { latest.value }
        if (acted) pipeline.userActed()
        val adapters = built.adapters.map { it.id }.toSet()
        launch(start = CoroutineStart.UNDISPATCHED) { pipeline.events.collect { eventFlow.tryEmit(it) } }
        launch(start = CoroutineStart.UNDISPATCHED) {
            combine(pipeline.state, latest) { track, now ->
                PipelineView(
                    track = track ?: view.value.track?.let(::superseded),
                    needsKey = KEYED_SOURCES.filter { now.isEnabled(it) && it !in adapters }.toSet(),
                    aiReady = built.ai != null,
                )
            }.collect { view.value = it }
        }
        current.value = pipeline
        try {
            pipeline.follow(changes(latest))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Kept stopped rather than restarted, which could repeat the failing run's requests.
            onFailure(e)
            awaitCancellation()
        } finally {
            current.compareAndSet(pipeline, null)
        }
    }

    // The previous pipeline's track, kept on screen until the new one publishes: no longer one a
    // tap on Write would write, since its run is gone.
    private fun superseded(track: TrackState): TrackState =
        if (track.insight is InsightState.Idle) track.copy(insight = InsightState.Idle(canWrite = false)) else track

    // The tracks the pipeline may run: none while lookups are not allowed (D6), the one shown as
    // soon as they are again, and each change while they are.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun changes(latest: StateFlow<Settings>): Flow<TrackChange?> =
        combine(visible, latest) { shown, now -> shown || now.lookUpInBackground }
            .distinctUntilChanged()
            .flatMapLatest { allowed -> if (allowed) nowPlaying.map { it?.let(::TrackChange) } else emptyFlow() }

    // What the pipeline is built from, besides the keys.
    private data class BuildInput(
        val engines: Set<WebEngine>,
        val provider: ProviderId?,
    )

    companion object {
        /** The sources looked up only with the user's key (design, *Source adapters*). */
        val KEYED_SOURCES: Set<SourceId> = setOf(SourceId.LASTFM, SourceId.WEB)
    }
}
