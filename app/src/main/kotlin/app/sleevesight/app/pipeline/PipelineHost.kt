package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.Gathered
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.Pipeline
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.RunControl
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.RunStep
import app.sleevesight.core.pipeline.TrackChange
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.PrepareNextTrack
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the home screen (#52) and the diagnostics screen (#54) read of the pipeline.
 *
 * @property track the current track's state, null before the first run. It stays through a
 *   rebuild of the pipeline (a key saved, say) until the new pipeline publishes its own, and
 *   offers Write only while the coordinator does ([CoordinatorState.offersWrite]).
 * @property needsKey the keyed sources (Last.fm, web search) switched on under *Sources* (web
 *   search with an engine switched on, `Settings.isEnabled`) that are not looked up because their
 *   key is not saved, or cannot be read: their state is that they need a key, not a failure. They
 *   have no entry in [TrackState.sources], which holds one per adapter, and a source needs its key
 *   for an adapter.
 * @property aiReady whether an insight can be written: an AI service is chosen, it has a client,
 *   and its key is saved and readable.
 * @property rewrite whether Write is offered beside the written insight shown, to write it again: a
 *   rebuild changed its card's key (new evidence, another service), so its run found no card. The
 *   card paid for (the ledger's, with the evidence it cites) is shown whenever the track's run has
 *   no insight of its own, offered or not ([cardShown]); this says only whether Write is offered
 *   beside it. The card shows beside the run's own source states, with the evidence it cites.
 * @property cardShown whether the insight shown is the card paid for, from the coordinator's ledger,
 *   in place of an insight the track's run lacks: then [track]'s evidence is the evidence that card
 *   cites, found when it was written, not what the sources found now. Worked out with the card
 *   lookup itself, so it holds whether Write is offered beside the card ([rewrite]) or not (insights
 *   off, no AI service), and whether a rewrite failed ([rewriteFailed]); kept with [track] while a
 *   rebuilt pipeline has not published.
 * @property rewriteFailed why the run's own insight failed, when the card paid for is shown in its
 *   place: the rewrite of that card failed (its call failed, or the run failed while writing) or
 *   was stopped ([PipelineHost.STOPPED]), and the card written before is shown again, with
 *   Rewrite while the coordinator offers Write again ([rewrite]). Null otherwise.
 * @property retry whether Write is offered again beside the insight shown, which failed or was
 *   stopped (#163): a tap is a new paid call. A run that failed while writing (a bug) has ended,
 *   and offers nothing.
 * @property writeKey the key of the run Write (or Rewrite) is offered for, which a tap carries
 *   back ([PipelineHost.write]); null while none is offered.
 * @property stopCall the model call under way that Stop is offered for, which a tap on Stop
 *   carries back ([PipelineHost.stop]); null otherwise.
 * @property offline whether the phone is offline, as the coordinator last heard it
 *   ([CoordinatorState.online], #192): its unreachable sources stay so until it is back online,
 *   and the playing track's are then asked again.
 * @property lapsed whether the Write offered is offered only because automatic writing paused
 *   ([CoordinatorState.lapsed], #203): the app was not on screen for more track changes than
 *   *Saver* names, so the first action expired, and a tap resumes it.
 * @property paused whether automatic writing is paused by *Saver* ([CoordinatorState.expired],
 *   #203): the first action expired, and nothing is written without a tap until the user acts
 *   again. The insight card's toggle (#553) shows it, whether or not Write is offered ([lapsed]).
 */
data class PipelineView(
    val track: TrackState? = null,
    val needsKey: Set<SourceId> = emptySet(),
    val aiReady: Boolean = false,
    val rewrite: Boolean = false,
    val cardShown: Boolean = false,
    val rewriteFailed: String? = null,
    val retry: Boolean = false,
    val writeKey: RunKey? = null,
    val stopCall: CallId? = null,
    val offline: Boolean = false,
    val lapsed: Boolean = false,
    val paused: Boolean = false,
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
 * Nothing is read, built or sent before [acknowledged]: the session layer's reports ([changes]: each
 * track with why it came to play, D5), the settings and the keys are collected only after it. A
 * rebuild follows a key saved, replaced or removed ([keyChanges]), a web engine switched or another
 * AI service chosen; no pipeline is built until the keys' first read succeeds. A pipeline that
 * cannot be built is reported to [onFailure] and none runs until the next rebuild; a run that fails
 * (a bug: the stages map their failures to outcomes) is reported and ends, and the next track runs.
 *
 * [next] is the next item in the shown player's queue (`NextInQueue`), which is prepared ahead
 * while *Proactive* is on (D13): the coordinator starts and grants the preparation
 * ([Pipeline.prepare]) only once the current run's requests are over, and cancels it when the
 * next item or the playing track changes.
 *
 * [state] is what the UI renders; [events] the diagnostics stream, across rebuilds;
 * [lookupsAllowed] the D6 gate, which the session layer's `http` art waits on too. [onAction] is
 * told when the coordinator takes a user's action (a tap on Write), for the time since the last
 * one that each track change is recorded with (D15).
 *
 * *Saver* reaches the coordinator as `PauseAutoWriteChanged`, from the
 * settings as *Look up in the background* does (#203).
 *
 * [online] is whether the phone is online (its default network has validated internet access),
 * collected after the acknowledgement: when it comes back, the playing track's run asks again the
 * sources it found unreachable (#192), as the coordinator decides.
 *
 * Each run and preparation starts marked with [epoch]'s generation, so what it finds is kept only
 * if *Clear history* has not come since (#547). [forget] is that clear's part here: the track's
 * state, the insight shown and the coordinator's ledger go, at once.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The inputs of one loop, each a seam for the tests.
class PipelineHost(
    private val acknowledged: Flow<Boolean>,
    private val changes: Flow<TrackChange?>,
    private val latestPlaying: () -> NowPlaying?,
    private val settings: Flow<Settings>,
    private val keyChanges: Flow<Unit>,
    private val readKeys: suspend (ProviderId?) -> PipelineKeys,
    private val parts: (PipelineKeys, Settings) -> PipelineParts,
    private val normalizer: Normalizer,
    private val onFailure: (Throwable) -> Unit = {},
    private val onAction: () -> Unit = {},
    private val next: Flow<NowPlaying?> = emptyFlow(),
    private val online: Flow<Boolean> = emptyFlow(),
    private val epoch: ClearEpoch = ClearEpoch(),
) {
    private val queue = Channel<CoordinatorEvent>(Channel.UNLIMITED)

    // The callers of [forget] waiting for the loop to take their clear, in order.
    private val forgetting = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()

    // The clears the view was last published for, and the track state shown before the last of
    // them, which is never shown again (#547).
    private var viewClears = 0L
    private var forgotten: TrackState? = null

    // One per process, never recreated or persisted: its `acted` starting false is what makes a
    // cold start wait for the first action (D5, #190); only its expiry clears it after (#203).
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
    private val preparations = HashMap<RunId, PreparationHandle>()
    private var building: Job? = null

    /** What the UI renders. */
    val state: StateFlow<PipelineView> = view.asStateFlow()

    /** For the tests: each view as it is published, none conflated. */
    @Volatile
    internal var published: ((PipelineView) -> Unit)? = null

    /** For the tests: the preparations whose handles the host holds. */
    internal val preparationsHeld: Int get() = preparations.size

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

    /**
     * The user tapped Write (or Rewrite) offered under [key] ([PipelineView.writeKey]) beside the
     * track [shown]: the coordinator writes that run's insight if it still offers Write. A tap on a
     * track the session layer no longer shows ([latestPlaying], read now: its report may not have
     * reached the coordinator yet) is dropped, so it never pays for the track just left.
     */
    fun write(
        key: RunKey,
        shown: NowPlaying,
    ) {
        val latest = latestPlaying() ?: return
        if (!sameInsightTrack(shown, latest)) return
        queue.trySend(CoordinatorEvent.WriteTapped(key))
    }

    /**
     * The user tapped Stop offered for [call] ([PipelineView.stopCall]): the coordinator stops that
     * model call if it is still under way, and no other. Unlike a tap on Write, it is never dropped
     * for a track left meanwhile: stopping spends nothing.
     */
    fun stop(call: CallId) {
        queue.trySend(CoordinatorEvent.StopTapped(call))
    }

    /**
     * *Clear history*'s part here (#547): the run and the preparation cancelled, the live
     * pipeline's state and the insight shown gone, the coordinator's ledger emptied, and the track
     * playing not looked up again until another one plays (no paid call on its own). Returns once
     * the loop has taken it. The host must have been started ([start]).
     */
    suspend fun forget() {
        val done = CompletableDeferred<Unit>()
        forgetting.add(done)
        queue.send(CoordinatorEvent.Cleared)
        done.await()
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
                latest.map { it.pauseAutoWriteAfter }.distinctUntilChanged().collect {
                    queue.send(CoordinatorEvent.PauseAutoWriteChanged(it))
                }
            }
            launch {
                // What the parts are built with: the keys, the web engines, the AI service, and *Show
                // genres* (#444), whose adapters ask for tags or not.
                val inputs = latest.map { Triple(it.webEngines, it.aiProvider, it.showGenres) }.distinctUntilChanged()
                combine(keyChanges, inputs) { _, _ -> }.collect { queue.send(CoordinatorEvent.RebuildRequested) }
            }
            launch {
                val preparing = latest.map { it.prepareNextTrack != PrepareNextTrack.OFF }.distinctUntilChanged()
                combine(next, preparing) { track, on -> track?.takeIf { on } }
                    .distinctUntilChanged()
                    .collect { queue.send(CoordinatorEvent.NextChanged(it)) }
            }
            launch {
                online.distinctUntilChanged().collect { queue.send(CoordinatorEvent.NetworkChanged(it)) }
            }
            launch {
                changes.collect { change ->
                    change?.let {
                        val playing = it.nowPlaying
                        queue.send(CoordinatorEvent.Reported(RunKey.of(normalizer, playing), playing, it.cause))
                    }
                }
            }
        }

    // The one consumer of the queue: each event through the coordinator, then its effects.
    private suspend fun loop(scope: CoroutineScope) {
        for (event in queue) {
            // A preparation that ended needs its handle no more: no effect names it again.
            if (event is CoordinatorEvent.PreparationEnded) preparations.remove(event.prep)
            // The pipelines forget their state before the coordinator's clear is published, so the
            // view never pairs that clear with a state from before it.
            if (event == CoordinatorEvent.Cleared) {
                live.value?.pipeline?.forget()
                built.values.forEach { (pipeline, _) -> pipeline.forget() }
                failed.value = null
            }
            val effects = coordinator.on(event)
            snapshot.value = coordinator.state
            effects.forEach { perform(it, scope) }
            if (event == CoordinatorEvent.Cleared) forgetting.poll()?.complete(Unit)
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
            is CoordinatorEffect.Grant -> (runs[effect.run] ?: preparations[effect.run])?.grant(effect.step)
            is CoordinatorEffect.Decide -> runs[effect.run]?.decided(effect.write)
            is CoordinatorEffect.StopCall -> runs[effect.call.run]?.stop(effect.call.number)
            CoordinatorEffect.Acted -> onAction()
            is CoordinatorEffect.StartPreparation -> startPreparation(effect)
            is CoordinatorEffect.CancelPreparation -> preparations.remove(effect.prep)?.job?.cancel()
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
        preparations.clear()
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
        val handle = RunHandle(effect.run)
        runs[effect.run] = handle
        failed.value = null
        handle.job =
            current.scope.launch(epoch.mark()) {
                try {
                    current.pipeline.run(effect.nowPlaying, handle, effect.again)
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

    // A preparation of the next track on the live pipeline; a failure is logged by its type and
    // ends it, as a run's does.
    private fun startPreparation(effect: CoordinatorEffect.StartPreparation) {
        val current = live.value ?: return
        val handle = PreparationHandle(effect.prep)
        preparations[effect.prep] = handle
        handle.job =
            current.scope.launch(epoch.mark()) {
                try {
                    current.pipeline.prepare(effect.next, handle)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    onFailure(e)
                } finally {
                    queue.trySend(CoordinatorEvent.PreparationEnded(effect.prep))
                }
            }
    }

    // What a run and a preparation share: they ask the coordinator for each step, and wait. A step
    // can be asked for again once it has ended (a preparation's second try, #191), so each request
    // waits for a grant of its own: the n-th request of a step for the n-th grant of it. The
    // coordinator grants a step once for each request, after it (`asked`, cleared as it grants),
    // so no grant is lost, or reaches another request than its own.
    private abstract inner class StepHandle(
        protected val id: RunId,
    ) : RunControl {
        var job: Job? = null
        private val gates = ConcurrentHashMap<Pair<RunStep, Int>, CompletableDeferred<Unit>>()

        // Per step, the requests made, touched only by the run; the grants, only by the loop.
        private val stepRequests = IntArray(RunStep.entries.size)
        private val stepGrants = IntArray(RunStep.entries.size)

        private fun gate(
            step: RunStep,
            number: Int,
        ): CompletableDeferred<Unit> = gates.computeIfAbsent(step to number) { CompletableDeferred() }

        // A step other than the model call, which a preparation is never granted.
        open fun grant(step: RunStep) {
            gate(step, ++stepGrants[step.ordinal]).complete(Unit)
        }

        override suspend fun await(step: RunStep) {
            val number = ++stepRequests[step.ordinal]
            queue.send(CoordinatorEvent.StepRequested(id, step))
            gate(step, number).await()
            gates.remove(step to number)
        }

        override fun ended(step: RunStep) {
            queue.trySend(CoordinatorEvent.StepEnded(id, step))
        }
    }

    // A preparation's control: it never writes, and is never granted a model call.
    private inner class PreparationHandle(
        id: RunId,
    ) : StepHandle(id) {
        override suspend fun decide(settings: Settings): Boolean = false

        override val waitsForTap: Boolean = false

        override suspend fun awaitTap() = Unit

        override fun decidedWithoutCall() = Unit
    }

    // One model call of a run: the tap that grants it, and the Stop that ends it.
    private class CallGate {
        val granted = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
    }

    // One run's control: it asks the coordinator, and waits for its answer. Its model calls are
    // numbered from 1 on every side, in the same order: the coordinator as it grants them
    // (`CallId`), the loop as it carries out each grant (a tap's Grant, or Decide to write), the
    // run as it makes each, after its grant. A Stop names its call's number, so it reaches that
    // call alone.
    private inner class RunHandle(
        id: RunId,
    ) : StepHandle(id) {
        private val decision = CompletableDeferred<Boolean>()
        private val calls = ConcurrentHashMap<Int, CallGate>()

        // The calls granted, touched only by the loop; the calls made, only by the run.
        private var granted = 0
        private var made = 0

        private fun call(number: Int): CallGate = calls.computeIfAbsent(number) { CallGate() }

        override fun grant(step: RunStep) {
            if (step == RunStep.MODEL_CALL) call(++granted).granted.complete(Unit) else super.grant(step)
        }

        fun decided(write: Boolean) {
            if (write) call(++granted).granted.complete(Unit)
            decision.complete(write)
        }

        fun stop(number: Int) {
            call(number).stopped.complete(Unit)
        }

        override suspend fun decide(settings: Settings): Boolean {
            val decides = WriteSettings.of(settings)
            queue.send(CoordinatorEvent.InsightAsked(id, decides.writesOn, decides.allowModel))
            return decision.await().also { write -> if (write) made++ }
        }

        override val waitsForTap: Boolean = true

        override suspend fun awaitTap() {
            call(made + 1).granted.await()
            made++
        }

        override suspend fun awaitRetry(): Boolean {
            queue.send(CoordinatorEvent.RetryAsked(id))
            awaitTap()
            return true
        }

        override suspend fun awaitStop() = call(made).stopped.await()

        override fun decidedWithoutCall() {
            queue.trySend(CoordinatorEvent.DecidedWithoutCall(id))
        }

        override fun nothingToNarrate(settings: Settings) {
            queue.trySend(CoordinatorEvent.DecidedWithoutCall(id, WriteSettings.of(settings)))
        }

        override fun gathered(gathered: Gathered) {
            queue.trySend(CoordinatorEvent.SourcesGathered(id, gathered))
        }

        override fun cardShown(
            card: InsightState.Written?,
            evidence: EvidenceSet,
        ) {
            queue.trySend(CoordinatorEvent.CardShown(id, card?.let { Card(it, evidence) }))
        }

        override fun cardWritten(
            card: InsightState.Written,
            evidence: EvidenceSet,
        ) {
            queue.trySend(CoordinatorEvent.CardWritten(id, Card(card, evidence)))
        }
    }

    // The view: the live pipeline's track (or the last one, until it publishes), offering Write
    // only while the coordinator does.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun publish(latest: StateFlow<Settings>) {
        val tracks = live.flatMapLatest { it?.pipeline?.state ?: flowOf(null) }
        combine(tracks, live, snapshot, latest, failed) { track, current, owner, now, failedKey ->
            // A clear since the view was published (#547): nothing shown before it is shown again,
            // not the track shown, nor a state the pipeline published before it that this has not
            // yet seen replaced.
            val fresh = owner.clears == viewClears
            if (!fresh) {
                viewClears = owner.clears
                forgotten = track
            }
            val own = track?.takeUnless { it === forgotten }
            // Until the live pipeline publishes, the track shown before, with what it said of a
            // rewrite that failed and of the card paid for: the track shown before already carries
            // that card's insight and evidence (the lookup finds no room for it again, and nothing
            // here replaces a written insight), so whether it is the card goes with it, and the
            // note and the card stay in step.
            val before = if (fresh) view.value else PipelineView()
            val shown =
                own?.let { shownTrack(it, owner, failedKey) }
                    ?: before.track
                        ?.let { shownTrack(it, owner, failedKey) }
                        ?.let { Shown(it.track, before.rewriteFailed, before.cardShown) }
                    ?: Shown(null, null)
            val insight = shown.track?.insight
            PipelineView(
                track = shown.track,
                rewrite = owner.rewrites() && insight is InsightState.Written,
                cardShown = shown.cardShown,
                needsKey = needsKey(current?.parts, now),
                aiReady = current?.parts?.let { it.ai != null } ?: view.value.aiReady,
                rewriteFailed = shown.rewriteFailed,
                retry = owner.writeKey != null && (insight is InsightState.Failed || insight == InsightState.Stopped),
                writeKey = owner.writeKey,
                stopCall = owner.stopCall,
                offline = !owner.online,
                lapsed = owner.lapsed,
                paused = owner.expired,
            )
        }.collect {
            view.value = it
            published?.invoke(it)
        }
    }

    // A track as shown, why its own insight failed when the card paid for is in its place, and
    // whether that card is.
    private class Shown(
        val track: TrackState?,
        val rewriteFailed: String?,
        val cardShown: Boolean = false,
    )

    // The track as shown: Write withdrawn unless offered; a run that failed while writing as
    // failed; the card paid for (from the ledger) in place of an insight its run lacks, saying so
    // when the run's own failed (a rewrite); and the header from the ledger's latest report.
    private fun shownTrack(
        track: TrackState,
        owner: CoordinatorState,
        failedKey: RunKey?,
    ): Shown {
        val own = failing(offering(track, owner), failedKey)
        val card = cardFor(owner, own)?.takeIf { lacksInsight(own) }
        val failure =
            when (val insight = own.insight) {
                is InsightState.Failed -> insight.reason
                InsightState.Stopped -> STOPPED
                else -> null
            }?.takeIf { card != null }
        return Shown(header(if (card != null) withCard(own, card) else own, owner), failure, cardShown = card != null)
    }

    private fun needsKey(
        made: PipelineParts?,
        now: Settings,
    ): Set<SourceId> {
        val adapters = made?.adapters?.map { it.id }?.toSet() ?: return view.value.needsKey
        return KEYED_SOURCES.filter { now.isEnabled(it) && it !in adapters }.toSet()
    }

    // The card paid for, from the ledger, if [track] is of its written track.
    private fun cardFor(
        owner: CoordinatorState,
        track: TrackState,
    ): Card? = owner.ledger?.takeIf { it.written && sameInsightTrack(it.track, track.nowPlaying) }?.card

    // Whether [track]'s own insight leaves room for the card paid for: not one written or being written.
    private fun lacksInsight(track: TrackState): Boolean =
        track.insight !is InsightState.Written && track.insight !is InsightState.Writing

    private fun withCard(
        track: TrackState,
        card: Card,
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

        /** [PipelineView.rewriteFailed] for a rewrite that was stopped. */
        const val STOPPED: String = "stopped"
    }
}
