package io.github.wkusnierczyk.sleevesight.store

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.pipeline.CardKey
import io.github.wkusnierczyk.sleevesight.core.pipeline.PipelineCache
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * The pipeline's [PipelineCache] over [caches]: the retrieval cache for source results, the
 * synthesis cache for written cards, with the track's retrieval key ([RetrievalCache.keyOf]) as a
 * card's entity key.
 * [Caches] makes blocking database calls, so each runs in [io]: the caller's
 * I/O dispatcher (`Dispatchers.IO` on the JVM), which `:core` and `:store` do not name, being
 * Multiplatform-ready.
 */
class StorePipelineCache(
    private val caches: Caches,
    private val io: CoroutineContext,
) : PipelineCache {
    override suspend fun result(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult? = withContext(io) { caches.retrieval.get(adapter, track, duration)?.result }

    override suspend fun keep(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ) {
        withContext(io) { caches.retrieval.put(adapter, track, duration, result) }
    }

    override suspend fun card(key: CardKey): String? = withContext(io) { caches.synthesis.get(key.toStore())?.text }

    override suspend fun keep(
        key: CardKey,
        text: String,
    ) {
        withContext(io) { caches.synthesis.put(key.toStore(), text) }
    }

    private fun CardKey.toStore(): SynthesisKey =
        SynthesisKey(
            kind = kind,
            entityKey = caches.retrieval.keyOf(track, duration),
            evidenceFingerprint = evidenceFingerprint,
            promptVersion = promptVersion,
            styleHash = styleHash,
            provider = provider,
            model = model,
            modelKnowledge = modelKnowledge,
            effort = effort,
        )
}
