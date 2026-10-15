package io.github.wkusnierczyk.sleevesight.core.pipeline

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceSet
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.genres.GenreVocabulary
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.NowPlaying
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** What one run is about: the track as the player reports it and normalized, and its settings. */
internal class RunInput(
    val settings: Settings,
    val nowPlaying: NowPlaying,
    val track: NormalizedTrack,
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
    private val taps: StateFlow<Long>,
    private val onEvent: (PipelineEvent) -> Unit,
    private val publish: (TrackState) -> Unit,
) : RunSink {
    private val settings = input.settings
    private val nowPlaying = input.nowPlaying
    private val track = input.track
    private val key = track.lookupKey
    private val enabled = parts.adapters.filter { settings.isEnabled(it.id) }
    private val state =
        MutableStateFlow(
            TrackState(
                nowPlaying = nowPlaying,
                normalized = track,
                sources =
                    parts.adapters.associate { adapter ->
                        adapter.id to if (adapter in enabled) SourceState.Pending else SOURCE_OFF
                    },
            ),
        )

    private val guard = CacheGuard(parts.cache, key, onEvent)

    /**
     * Gathers the evidence, then the insight: written at once when [autoWrite]; else, when
     * [waitForTap], once the user taps Write; else not at all.
     */
    suspend fun run(
        autoWrite: Boolean,
        waitForTap: Boolean,
    ): TrackState {
        val tapsAtStart = taps.value
        try {
            emit(PipelineEvent.Received(nowPlaying))
            emit(PipelineEvent.Normalized(track))
            publish(state.value)
            gather()
            val evidence = assemble()
            InsightWriter(parts, time, input, evidence, guard, this).write(
                autoWrite = autoWrite,
                tap = if (waitForTap) suspend { taps.first { it > tapsAtStart } } else null,
            )
            return state.value
        } catch (e: CancellationException) {
            emit(PipelineEvent.Superseded(key))
            throw e
        }
    }

    override fun emit(event: PipelineEvent) = onEvent(event)

    override fun update(change: (TrackState) -> TrackState) {
        state.update(change)
        publish(state.value)
    }

    // The cache first; then the misses by name in parallel, with resolution alongside (D4).
    private suspend fun gather() {
        val misses = enabled.filter { adapter -> !fromCache(adapter) }
        if (misses.isEmpty()) return
        val resolution = CompletableDeferred<Resolution>()
        val made = CompletableDeferred<Boolean>()
        coroutineScope {
            resolve(resolution, made)
            for (adapter in misses) launch { lookup(adapter, resolution, made) }
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
        launch {
            val started = time.timeSource.markNow()
            var resolved: Resolution? = null
            try {
                resolved = parts.resolver.resolve(track, nowPlaying.duration)
                resolution.complete(resolved)
                emit(PipelineEvent.Resolved(key, resolved.outcome, started.elapsedNow()))
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
        land(adapter.id, landed)
        if (landed is SourceState.Done && landed.result.outcome != Outcome.Unreachable) {
            when {
                !made.await() -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, NOT_RESOLVED))
                }

                landed.result.provisional -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, PROVISIONAL))
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

    // The cited lines: the player's release, then each enabled source's verified lines, in the
    // adapters' order, so the same retrieval gives the same ids whatever order the sources land in.
    private fun assemble(): EvidenceSet {
        val results =
            parts.adapters.mapNotNull { (state.value.sources[it.id] as? SourceState.Done)?.result }
        val drafts =
            playerLines() + results.flatMap { it.evidence }.filter { settings.isEnabled(it.source) }
        val evidence = EvidenceSet.of(drafts, parts.hasher)
        val genres =
            results
                .flatMap { it.genres }
                .distinctBy { it.tag }
                .take(GenreVocabulary.MAX_LABELS)
        update { it.copy(evidence = evidence, genres = genres) }
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

        fun nameOf(e: Throwable): String = e::class.simpleName ?: "Exception"
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
