package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AutoWrite
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.Pipeline
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.RunControl
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.RunStep
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the home screen (#52) and the diagnostics screen (#54) read of the pipeline.
 *
 * @property track the current track's state, null before the first run. It stays through a
 *   rebuild of the pipeline (a key saved, say) until the new pipeline publishes its own, and
 *   offers Write only while the coordinator does ([CoordinatorState.offersWrite]).
 * @property needsKey the keyed sources (Last.fm, web search) switched on under *Where to look* (web
 *   search with an engine switched on, `Settings.isEnabled`) that are not looked up because their
 *   key is not saved, or cannot be read: their state is that they need a key, not a failure. They
 *   have no entry in [TrackState.sources], which holds one per adapter, and a source needs its key
 *   for an adapter.
 * @property aiReady whether an insight can be written: an AI service is chosen, it has a client,
 *   and its key is saved and readable.
 * @property rewrite whether Write is offered beside a written insight, to write it again: a rebuild
 *   changed its card's key (new evidence, another service), so its run found no card, and the card
 *   paid for is shown, with the evidence it cites, until a tap writes the new one.
 */
data class PipelineView(
    val track: TrackState? = null,
    val needsKey: Set<SourceId> = emptySet(),
    val aiReady: Boolean = false,
    val rewrite: Boolean = false,
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
 * The shared pipeline run in the app (#51; architecture, *How the app runs it*): the carrier of
 * [PipelineCoordinator], which decides everything. It turns what happens into the coordinator's
 * events, on one queue processed one at a time, and carries out its effects: the debounce timers,
 * building pipelines from the keys ([readKeys], [parts]), starting and cancelling runs on the live
 * pipeline ([Pipeline.run]), and granting their steps. Each run asks the coordinator through its
 * [RunControl] before each step that sends requests or spends, and reports when one ends, from a
 * `finally`.
 *
 * Nothing is read, built or sent before [acknowledged]: the session layer's reports, the settings
 * and the keys are collected only after it. A rebuild follows a key saved, replaced or removed
 * ([keyChanges]), a web engine switched or another AI service chosen; no pipeline is built until
 * the keys' first read succeeds. A pipeline that cannot be built is reported to [onFailure] and
 * none runs until the next rebuild; a run that fails (a bug: the stages map their failures to
 * outcomes) is reported and ends, and the next track runs.
 *
 * [state] is what the UI renders; [events] the diagnostics stream, across rebuilds;
 * [lookupsAllowed] the D6 gate, which the session layer's `http` art waits on too.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The inputs of one loop, each a seam for the tests.
class PipelineHost(
    private val acknowledged: Flow<Boolean>,
    private val nowPlaying: Flow<NowPlaying?>,
    private val settings: Flow<Settings>,
    private val keyChanges: Flow<Unit>,
    private val readKeys: suspend (ProviderId?) -> PipelineKeys,
    private val parts: (PipelineKeys, Settings) -> PipelineParts,
    private val normalizer: Normalizer,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val queue = Channel<CoordinatorEvent>(Channel.UNLIMITED)
    private val coordinator = PipelineCoordinator()
    private val snapshot = MutableStateFlow(coordinator.state)
    private val view = MutableStateFlow(PipelineView())
    private val eventFlow =
        MutableSharedFlow<PipelineEvent>(
            extraBufferCapacity = Pipeline.EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // The pipeline that runs, with what it was built from and the scope of its runs.
    private class Live(
        val pipeline: Pipeline,
        val parts: PipelineParts,
        val scope: CoroutineScope,
    )

    private val live = MutableStateFlow<Live?>(null)

    // The track whose run failed during its insight, shown as failed until another run starts.
    private val failed = MutableStateFlow<RunKey?>(null)

    // The pipelines built, until the coordinator swaps one in. A build superseded before it was
    // swapped in stays here until the next swap, which clears them all.
    private val built = ConcurrentHashMap<Long, Pair<Pipeline, PipelineParts>>()

    // The settings once read (after the acknowledgement); builds read them.
    @Volatile
    private var latestSettings: StateFlow<Settings>? = null

    // Touched only by the loop.
    private val runs = HashMap<RunId, RunHandle>()
    private var building: Job? = null

    /** What the UI renders. */
    val state: StateFlow<PipelineView> = view.asStateFlow()

    /** For the tests: each view as it is published, none conflated. */
    @Volatile
    internal var published: ((PipelineView) -> Unit)? = null

    /** The diagnostics stream of every pipeline built, in order; a slow collector loses the oldest. */
    val events: SharedFlow<PipelineEvent> = eventFlow.asSharedFlow()

    /**
     * Whether requests may start (D6): once the first run is acknowledged, while the app is visible
     * or *Look up in the background* is on.
     */
    val lookupsAllowed: Flow<Boolean> = snapshot.map { it.allowed }.distinctUntilChanged()

    /** An activity started (`MainActivity.onStart`). */
    fun activityStarted() {
        queue.trySend(CoordinatorEvent.ActivityStarted)
    }

    /** An activity stopped (`MainActivity.onStop`), for a configuration change when [changingConfigurations]. */
    fun activityStopped(changingConfigurations: Boolean) {
        queue.trySend(CoordinatorEvent.ActivityStopped(changingConfigurations))
    }

    /** The user tapped Write: the coordinator writes the current track's insight if it offers Write. */
    fun write() {
        queue.trySend(CoordinatorEvent.WriteTapped)
    }

    /** Runs until [scope] is cancelled; called once. */
    fun start(scope: CoroutineScope) {
        scope.launch { run() }
    }

    private suspend fun run() =
        coroutineScope {
            launch { loop(this) }
            acknowledged.first { it }
            queue.send(CoordinatorEvent.Acknowledged)
            val latest = settings.stateIn(this)
            latestSettings = latest
            launch { publish(latest) }
            launch {
                latest.map { it.lookUpInBackground }.distinctUntilChanged().collect {
                    queue.send(CoordinatorEvent.BackgroundChanged(it))
                }
            }
            launch {
                val inputs = latest.map { it.webEngines to it.aiProvider }.distinctUntilChanged()
                combine(keyChanges, inputs) { _, _ -> }.collect { queue.send(CoordinatorEvent.RebuildRequested) }
            }
            launch {
                nowPlaying.collect { playing ->
                    playing?.let { queue.send(CoordinatorEvent.Reported(RunKey.of(normalizer, it), it)) }
                }
            }
        }

    // The one consumer of the queue: each event through the coordinator, then its effects.
    private suspend fun loop(scope: CoroutineScope) {
        for (event in queue) {
            val effects = coordinator.on(event)
            snapshot.value = coordinator.state
            effects.forEach { perform(it, scope) }
        }
    }

    private fun perform(
        effect: CoordinatorEffect,
        scope: CoroutineScope,
    ) {
        when (effect) {
            is CoordinatorEffect.StartTimer -> {
                scope.launch {
                    delay(Pipeline.DEBOUNCE)
                    queue.send(CoordinatorEvent.Settled(effect.report))
                }
            }

            is CoordinatorEffect.Build -> build(effect.build, scope)
            is CoordinatorEffect.Swap -> swap(effect, scope)
            is CoordinatorEffect.StartRun -> startRun(effect)
            is CoordinatorEffect.CancelRun -> runs.remove(effect.run)?.job?.cancel()
            is CoordinatorEffect.Grant -> runs[effect.run]?.grant(effect.step)
            is CoordinatorEffect.Decide -> runs[effect.run]?.decision?.complete(effect.write)
        }
    }

    private fun build(
        number: Long,
        scope: CoroutineScope,
    ) {
        building?.cancel()
        building =
            scope.launch {
                val ok =
                    try {
                        val latest = checkNotNull(latestSettings)
                        val now = latest.value
                        val made = parts(readKeys(now.aiProvider), now)
                        built[number] = Pipeline(made) { latest.value } to made
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        onFailure(e)
                        false
                    }
                // Without suspending: a build that finished after it was overtaken still says so,
                // and the coordinator ignores it.
                queue.trySend(CoordinatorEvent.RebuildFinished(number, ok))
            }
    }

    private fun swap(
        effect: CoordinatorEffect.Swap,
        scope: CoroutineScope,
    ) {
        live.value?.scope?.cancel()
        runs.clear()
        val made = built.remove(effect.build)
        built.clear()
        if (!effect.built || made == null) {
            live.value = null
            return
        }
        val (pipeline, madeParts) = made
        val runScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        runScope.launch(start = CoroutineStart.UNDISPATCHED) { pipeline.events.collect { eventFlow.tryEmit(it) } }
        live.value = Live(pipeline, madeParts, runScope)
    }

    private fun startRun(effect: CoordinatorEffect.StartRun) {
        val current = live.value ?: return
        val handle = RunHandle(effect.run, effect.cause)
        runs[effect.run] = handle
        failed.value = null
        handle.job =
            current.scope.launch {
                try {
                    current.pipeline.run(effect.nowPlaying, handle)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    onFailure(e)
                    failed.value = RunKey.of(normalizer, effect.nowPlaying)
                } finally {
                    // However it ends, nothing in the coordinator waits for it any more.
                    queue.trySend(CoordinatorEvent.RunEnded(effect.run))
                }
            }
    }

    // One run's control: it asks the coordinator, and waits for its answer.
    private inner class RunHandle(
        private val id: RunId,
        private val cause: ChangeCause,
    ) : RunControl {
        var job: Job? = null
        private val grants = RunStep.entries.associateWith { CompletableDeferred<Unit>() }
        val decision = CompletableDeferred<Boolean>()

        fun grant(step: RunStep) {
            grants.getValue(step).complete(Unit)
        }

        override suspend fun await(step: RunStep) {
            queue.send(CoordinatorEvent.StepRequested(id, step))
            grants.getValue(step).await()
        }

        override suspend fun decide(settings: Settings): Boolean {
            queue.send(CoordinatorEvent.InsightAsked(id, AutoWrite.writes(settings, cause, userHasActed = true)))
            return decision.await()
        }

        override val waitsForTap: Boolean = true

        override suspend fun awaitTap() = grants.getValue(RunStep.MODEL_CALL).await()

        override fun ended(step: RunStep) {
            queue.trySend(CoordinatorEvent.StepEnded(id, step))
        }

        override fun decidedWithoutCall() {
            queue.trySend(CoordinatorEvent.DecidedWithoutCall(id))
        }

        override fun cardShown() {
            queue.trySend(CoordinatorEvent.CardShown(id))
        }
    }

    // The view: the live pipeline's track (or the last one, until it publishes), offering Write
    // only while the coordinator does.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun publish(latest: StateFlow<Settings>) {
        val tracks = live.flatMapLatest { it?.pipeline?.state ?: flowOf(null) }
        combine(tracks, live, snapshot, latest, failed) { track, current, owner, now, failedKey ->
            track?.let { keep(it, owner) }
            PipelineView(
                track = shownTrack(track ?: view.value.track, owner, failedKey),
                rewrite = keptFor(owner) != null,
                needsKey = needsKey(current?.parts, now),
                aiReady = current?.parts?.let { it.ai != null } ?: view.value.aiReady,
            )
        }.collect {
            view.value = it
            published?.invoke(it)
        }
    }

    // The track as shown: Write only while offered, the card kept beside a rewrite offer, a failed
    // run as failed, and the header from the latest report.
    private fun shownTrack(
        track: TrackState?,
        owner: CoordinatorState,
        failedKey: RunKey?,
    ): TrackState? {
        val card = keptFor(owner)
        return track
            ?.let { offering(it, owner) }
            ?.let { if (card != null && it.insight is InsightState.Idle) withCard(it, card) else it }
            ?.let { failing(it, failedKey) }
            ?.let { header(it, owner) }
    }

    private fun needsKey(
        made: PipelineParts?,
        now: Settings,
    ): Set<SourceId> {
        val adapters = made?.adapters?.map { it.id }?.toSet() ?: return view.value.needsKey
        return KEYED_SOURCES.filter { now.isEnabled(it) && it !in adapters }.toSet()
    }

    // The card paid for, kept in memory for the ledger's written track while it stands: what a
    // rebuild's run that cannot find it (its card key changed) shows beside Write. Touched by the
    // publishing coroutine alone.
    private var kept: Pair<RunKey, TrackState>? = null

    private fun keep(
        track: TrackState,
        owner: CoordinatorState,
    ) {
        val ledger = owner.ledger?.takeIf { it.written && sameInsightTrack(it.track, track.nowPlaying) }
        when {
            ledger == null ->
                if (owner.ledger?.let { sameInsightTrack(it.track, track.nowPlaying) } !=
                    true
                ) {
                    kept = null
                }
            track.insight is InsightState.Written -> kept = ledger.key to track
        }
    }

    // The card to show beside a rewrite offer, if one is kept for the ledger's key; none when it
    // cannot be read (none was shown: the call failed or was cut off), and Write is offered alone.
    private fun keptFor(owner: CoordinatorState): TrackState? {
        val ledger = owner.ledger ?: return null
        return kept?.takeIf { owner.rewrites() && it.first == ledger.key }?.second
    }

    private fun withCard(
        track: TrackState,
        card: TrackState,
    ): TrackState = track.copy(insight = card.insight, evidence = card.evidence)

    // The header (artists, album, length) from the latest report of the ledger's insight track;
    // the run's input and its card's key stay its own.
    private fun header(
        track: TrackState,
        owner: CoordinatorState,
    ): TrackState {
        val shown = owner.ledger?.takeIf { sameInsightTrack(it.track, track.nowPlaying) }?.shown ?: return track
        return if (shown == track.nowPlaying) track else track.copy(nowPlaying = shown)
    }

    // A run that failed while writing shows as failed, not as writing for ever.
    private fun failing(
        track: TrackState,
        failedKey: RunKey?,
    ): TrackState {
        val stuck = track.insight is InsightState.Writing && failedKey == RunKey.of(track.normalized, track.nowPlaying)
        return if (stuck) track.copy(insight = InsightState.Failed(FAILED)) else track
    }

    // Write withdrawn unless the coordinator offers it for this track.
    private fun offering(
        track: TrackState,
        owner: CoordinatorState,
    ): TrackState {
        val withdrawn =
            (track.insight as? InsightState.Idle)?.canWrite == true &&
                !owner.offersWrite(RunKey.of(track.normalized, track.nowPlaying))
        return if (withdrawn) track.copy(insight = InsightState.Idle(canWrite = false)) else track
    }

    companion object {
        /** The sources looked up only with the user's key (design, *Source adapters*). */
        val KEYED_SOURCES: Set<SourceId> = setOf(SourceId.LASTFM, SourceId.WEB)

        /** The reason shown for an insight whose run failed. */
        const val FAILED: String = "failed"
    }
}
