package app.sleevesight.store

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.currentCoroutineContext
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
 *
 * A result that says something about an artist ([RetrievalCodec.aboutArtists]) is kept only while
 * [keeping] is on (*Keep what sources said about each artist*, #420), in turn with its clear; while
 * it is off, that source is asked again for each track, as the artist-level cache is, and one kept
 * before is not read.
 *
 * Nothing found by work started before *Clear history* is kept after it ([epoch], #547).
 */
class StorePipelineCache(
    private val caches: Caches,
    private val io: CoroutineContext,
    private val keeping: ArtistKeeping,
    private val epoch: ClearEpoch = ClearEpoch(),
) : PipelineCache {
    override suspend fun result(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult? {
        keeping.retryClear()
        return withContext(io) { caches.retrieval.get(adapter, track, duration)?.result }
            // One kept before the switch was turned off, which a clear that failed left.
            ?.takeUnless { RetrievalCodec.aboutArtists(it) && !keeping.on }
    }

    override suspend fun keep(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ) {
        val work = currentCoroutineContext()
        val put =
            suspend {
                epoch.ifCurrent(work) { withContext(io) { caches.retrieval.put(adapter, track, duration, result) } }
            }
        if (RetrievalCodec.aboutArtists(result)) keeping.ifOn { put() } else put()
    }

    override suspend fun card(key: CardKey): String? = withContext(io) { caches.synthesis.get(key.toStore())?.text }

    override suspend fun keptCard(key: CardKey): KeptCard? =
        withContext(io) { caches.synthesis.get(key.toStore())?.let { KeptCard(it.text, it.writtenAt) } }

    override suspend fun keep(
        key: CardKey,
        text: String,
    ) {
        val work = currentCoroutineContext()
        epoch.ifCurrent(work) { withContext(io) { caches.synthesis.put(key.toStore(), text) } }
    }

    private fun CardKey.toStore(): SynthesisKey =
        SynthesisKey(
            kind = kind,
            // A card about more than the track (a genre's reason, #523) keeps its subject after it,
            // past a line break: the retrieval key's parts are length-prefixed, so the two never blur.
            entityKey = caches.retrieval.keyOf(track, duration) + subject?.let { "\n$it" }.orEmpty(),
            evidenceFingerprint = evidenceFingerprint,
            promptVersion = promptVersion,
            styleHash = styleHash,
            provider = provider,
            model = model,
            modelKnowledge = modelKnowledge,
            effort = effort,
        )
}
