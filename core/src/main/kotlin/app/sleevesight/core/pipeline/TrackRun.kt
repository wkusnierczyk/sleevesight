package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.FreshLookups
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * What one run is about: the track as the player reports it and normalized, and its settings;
 * [again], what an earlier run of the track gathered, when it asks again the sources that were
 * unreachable then (#192); [lookAgain], when it looks every source up afresh (#617).
 */
internal class RunInput(
    val settings: Settings,
    val nowPlaying: NowPlaying,
    val track: NormalizedTrack,
    val again: Gathered? = null,
    val lookAgain: LookAgain? = null,
    val genresBefore: TrackGenres = TrackGenres(),
)

/** Where a run reports: diagnostics events, and changes to its state. */
internal interface RunSink {
    fun emit(event: PipelineEvent)

    fun update(change: (TrackState) -> TrackState)
}

/**
 * One track's run through the pipeline (see [Pipeline]), under one set of settings. [publish]
 * forwards each new state to the pipeline's, while this run is the current one; [onEvent] sends a
 * diagnostics event.
 */
internal class TrackRun(
    private val parts: PipelineParts,
    private val time: PipelineTime,
    private val input: RunInput,
    private val control: RunControl,
    private val onEvent: (PipelineEvent) -> Unit,
    private val publish: (TrackState) -> Unit,
) : RunSink {
    private val settings = input.settings
    private val nowPlaying = input.nowPlaying
    private val track = input.track
    private val key = track.lookupKey
    private val enabled = parts.adapters.filter { settings.isEnabled(it.id) }

    // Whether the run looks every source up afresh (#617).
    private val looksAgain = input.lookAgain != null

    // What an earlier run of the track found (#192), kept as it landed: every state of a source
    // still switched on but `Unreachable`, which is asked again, unless the run asks again only some
    // ([Gathered.asked], a *Retry*, #724). An adapter that threw (a bug, not the network) is not
    // asked again either; a source that was off then is looked up as usual.
    private val keptBefore: Map<SourceId, SourceState> =
        input.again
            ?.let { again ->
                again.sources.filter { (source, before) ->
                    enabled.any { it.id == source } && (keeps(before) || source in again.unreachable - again.asked)
                }
            }.orEmpty()

    // A resolution an earlier run of the track made, used again rather than asked for (#192).
    private val resolvedBefore =
        input.again?.resolution?.takeIf {
            it.outcome != Outcome.Unreachable && settings.isEnabled(SourceId.MUSICBRAINZ)
        }
    private val state =
        MutableStateFlow(
            TrackState(
                nowPlaying = nowPlaying,
                normalized = track,
                sources =
                    parts.adapters.associate { adapter ->
                        val fresh = if (adapter in enabled) SourceState.Pending else SOURCE_OFF
                        adapter.id to (keptBefore[adapter.id] ?: fresh)
                    },
                resolution = resolvedBefore,
                // A run that asks again shows the genres the run before it had (#772), until its own
                // are assembled, which replace them.
                genres = input.genresBefore,
            ),
        )

    private val guard = CacheGuard(parts.cache, key, onEvent)
    private val labels =
        TrackLabels(parts.musicBrainzLabels, parts.lastFmLabels, settings, key, this, parts.normalizer::foldForKey)

    /**
     * Gathers the evidence, then the insight: written at once when [control] decides so; else,
     * when it waits for a tap, once the user taps Write; else not at all. The genre labels that take
     * requests (MusicBrainz's artists', Last.fm's track's and album's) are asked for beside the
     * insight, after the evidence, so it does not wait for them; the run
     * ends when both have. Each step that sends requests starts once [control] grants it. A run that
     * looks again (#617) does all of it marked [FreshLookups], so no cache answers its lookups.
     */
    suspend fun run(): TrackState {
        try {
            emit(PipelineEvent.Received(nowPlaying))
            emit(PipelineEvent.Normalized(track))
            publish(state.value)
            withContext(if (looksAgain) FreshLookups else EmptyCoroutineContext) {
                gather()
                control.gathered(Gathered(state.value.sources, state.value.resolution))
                // Published as asked for with the assembled genres, before they may start (#772): a
                // panel says it is still looking.
                val later = labels.later(state.value)
                val evidence = assemble()
                control.assembled(evidence)
                launch {
                    control.await(RunStep.LABELS)
                    try {
                        labels.addLater(later)
                    } finally {
                        control.ended(RunStep.LABELS)
                    }
                }
                InsightWriter(parts, time, input, evidence, guard, this@TrackRun).write(control)
            }
            return state.value
        } catch (e: CancellationException) {
            emit(PipelineEvent.Superseded(key))
            throw e
        } finally {
            // Whatever ends the run, before its label requests start or while they run: none stays
            // asked for (#772).
            labels.settle()
        }
    }

    override fun emit(event: PipelineEvent) = onEvent(event)

    override fun update(change: (TrackState) -> TrackState) {
        state.update(change)
        publish(state.value)
    }

    // What an earlier run kept (#192); then the cache, but for a look again (#617); then the misses
    // by name in parallel, with resolution alongside (D4).
    private suspend fun gather() {
        input.again?.let { again ->
            val asked = again.asked.filter { source -> enabled.any { it.id == source } }
            if (asked.isNotEmpty()) emit(PipelineEvent.AskedAgain(key, asked.sortedBy { it.ordinal }))
        }
        if (looksAgain) emit(PipelineEvent.LookedAgain(key, enabled.map { it.id }))
        val misses = enabled.filter { adapter -> adapter.id !in keptBefore && (looksAgain || !fromCache(adapter)) }
        if (misses.isEmpty()) return
        control.await(RunStep.LOOKUPS)
        val resolution = CompletableDeferred<Resolution>()
        val made = CompletableDeferred<Boolean>()
        try {
            coroutineScope {
                resolve(resolution, made)
                for (adapter in misses) launch { lookup(adapter, resolution, made) }
            }
        } finally {
            control.ended(RunStep.LOOKUPS)
        }
    }

    private suspend fun fromCache(adapter: SourceAdapter): Boolean {
        val hit =
            guard.attempt("read ${adapter.id.tag}") { parts.cache.result(adapter, track, nowPlaying.duration) }
                ?: return false
        land(adapter.id, SourceState.Done(hit, cached = true))
        return true
    }

    private fun CoroutineScope.resolve(
        resolution: CompletableDeferred<Resolution>,
        made: CompletableDeferred<Boolean>,
    ) {
        if (!settings.isEnabled(SourceId.MUSICBRAINZ)) {
            resolution.complete(Resolution.NONE)
            made.complete(false)
            return
        }
        resolvedBefore?.let {
            resolution.complete(it)
            made.complete(true)
            return
        }
        launch {
            val started = time.timeSource.markNow()
            var resolved: Resolution? = null
            try {
                resolved =
                    parts.resolver.resolve(track, nowPlaying.duration).let { input.lookAgain?.resolution(it) ?: it }
                resolution.complete(resolved)
                emit(PipelineEvent.Resolved(key, resolved.outcome, started.elapsedNow(), resolved.failure))
                update { it.copy(resolution = resolved) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // The contract: the adapters' Deferred completes normally, whatever happens here.
                emit(PipelineEvent.SourceFailed(key, SourceId.MUSICBRAINZ, nameOf(e)))
            } finally {
                resolution.complete(Resolution.NONE)
                made.complete(resolved != null && resolved.outcome != Outcome.Unreachable)
            }
        }
    }

    private suspend fun lookup(
        adapter: SourceAdapter,
        resolution: CompletableDeferred<Resolution>,
        made: CompletableDeferred<Boolean>,
    ) {
        val landed =
            try {
                SourceState.Done(adapter.lookup(track, resolution), cached = false)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // A bug in one adapter must not take the others down; its type is logged.
                SourceState.Failed(nameOf(e))
            }
        // A look again (#617) shows what it found, or what was found before when it found nothing new.
        val shown = input.lookAgain?.kept(adapter.id, landed) ?: landed
        land(adapter.id, shown)
        if (landed is SourceState.Done && landed.result.outcome != Outcome.Unreachable) {
            when {
                !made.await() -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, NOT_RESOLVED))
                }

                landed.result.provisional -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, PROVISIONAL))
                }

                // A look again (#617) that shows what was found before, in whole or in part: the
                // entry kept stays, and the lines carried keep their own lifetime.
                shown !== landed -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, KEPT_BEFORE))
                }

                else -> {
                    guard.attempt(
                        "write ${adapter.id.tag}",
                    ) { parts.cache.keep(adapter, track, nowPlaying.duration, landed.result) }
                }
            }
        }
    }

    private fun land(
        source: SourceId,
        landed: SourceState,
    ) {
        when (landed) {
            is SourceState.Done -> {
                val result = landed.result
                emit(
                    PipelineEvent.SourceLanded(
                        key = key,
                        source = source,
                        outcome = result.outcome,
                        evidence = result.evidence.size,
                        unverified = result.unverified.size,
                        rejections = result.rejections.size,
                        latency = result.latency,
                        cached = landed.cached,
                    ),
                )
            }

            is SourceState.Failed -> emit(PipelineEvent.SourceFailed(key, source, landed.reason))
            SourceState.Pending -> Unit
        }
        update { it.copy(sources = it.sources + (source to landed)) }
    }

    // The cited lines: the player's release, then each enabled source's evidence lines (verified,
    // or web search's kept ones), in the adapters' order, so the same retrieval gives the same ids
    // whatever order the sources land in.
    // The genre labels: those of the label sources switched on, combined; never evidence.
    private fun assemble(): EvidenceSet {
        val results =
            parts.adapters.mapNotNull { (state.value.sources[it.id] as? SourceState.Done)?.result }
        val drafts =
            playerLines() + results.flatMap { it.evidence }.filter { settings.isEnabled(it.source) }
        val evidence = EvidenceSet.of(drafts, parts.hasher)
        // The genres and how their later requests stand, in one update with the evidence (#772).
        update {
            it.copy(evidence = evidence, genres = labels.of(it), laterLabels = labels.states(), genresAssembled = true)
        }
        emit(
            PipelineEvent.EvidenceAssembled(
                key = key,
                lines = evidence.lines.size,
                sources =
                    evidence.lines
                        .map { it.source }
                        .distinct()
                        .size,
                fingerprint = evidence.fingerprint,
            ),
        )
        return evidence
    }

    // The release the player reports, as the simulator words it (`P` evidence).
    private fun playerLines(): List<EvidenceDraft> =
        listOfNotNull(nowPlaying.album?.trim()?.takeIf { it.isNotEmpty() }).map { album ->
            EvidenceDraft(
                source = SourceId.PLAYER,
                scope = Scope.RELEASE,
                text = "This recording is on the release \"$album\".",
                url = null,
                retrievedAt = time.clock.now(),
            )
        }

    internal companion object {
        val SOURCE_OFF: SourceState =
            SourceState.Done(SourceResult(Outcome.SourceOff, emptyList(), Duration.ZERO), cached = false)

        const val NOT_RESOLVED: String = "the MusicBrainz resolution it was checked against could not be made"

        const val PROVISIONAL: String = "a request of its verification failed, so a later lookup may verify it"

        const val KEPT_BEFORE: String = "a look again shows lines found before, which keep their own lifetime (#617)"

        fun nameOf(e: Throwable): String = e::class.simpleName ?: "Exception"

        // Whether a source's state from an earlier run is kept rather than asked again (#192).
        fun keeps(before: SourceState): Boolean =
            when (before) {
                is SourceState.Done -> before.result.outcome !in setOf(Outcome.Unreachable, Outcome.SourceOff)
                is SourceState.Failed -> true
                SourceState.Pending -> false
            }
    }
}

/** Cache calls that log and carry on when the cache fails, as if it had missed. */
internal class CacheGuard(
    private val cache: PipelineCache,
    private val key: String,
    private val emit: (PipelineEvent) -> Unit,
) {
    suspend fun <T> attempt(
        operation: String,
        block: suspend PipelineCache.() -> T,
    ): T? =
        try {
            cache.block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            emit(PipelineEvent.CacheFailed(key, operation, TrackRun.nameOf(e)))
            null
        }
}
