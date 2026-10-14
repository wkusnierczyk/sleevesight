package io.github.wkusnierczyk.sleevesight.store

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import kotlin.time.Instant

/**
 * What source lookups gave, per source and lookup key ([NormalizedTrack.lookupKey]), kept for the
 * source's lifetime in [Settings.cacheLifetimes] (D9).
 *
 * It keeps the [SourceResult] the pipeline uses (outcome, evidence lines, unverified lines,
 * rejections, latency), never the raw response: that keeps it small, and keeps text the app does
 * not show (the rest of an article, a whole bio) out of storage. So a cached result depends on the
 * adapter's extraction rules too: each entry carries the adapter's [SourceAdapter.extractionVersion],
 * and one written under another version is a miss.
 *
 * Not kept: a result that is [Outcome.Unreachable] (a network error is not an answer) or
 * [Outcome.SourceOff], and anything for a source with no lifetime (the pseudo-sources). The
 * orchestration (#44) also does not keep a result verified against a resolution that could not be
 * made, since it would stay unverified after MusicBrainz is back.
 */
class RetrievalCache internal constructor(
    private val context: CacheContext,
) {
    /** The result [adapter] gave for [lookupKey], if kept, current and within its lifetime. */
    fun get(
        adapter: SourceAdapter,
        lookupKey: String,
    ): CachedResult? = get(adapter.id, lookupKey, adapter.extractionVersion)

    /** The result [source]'s adapter, at [extractionVersion], gave for [lookupKey], if kept and current. */
    fun get(
        source: SourceId,
        lookupKey: String,
        extractionVersion: Int,
    ): CachedResult? {
        val lifetime = context.settings().cacheLifetimes[source]
        val row = lifetime?.let { context.queries.retrievalGet(source.tag, lookupKey).executeAsOneOrNull() }
        if (lifetime == null || row == null) return null
        val now = context.clock.now()
        val storedAt = Instant.fromEpochMilliseconds(row.stored_at)
        val current =
            row.format == RetrievalCodec.FORMAT.toLong() &&
                row.extraction == extractionVersion.toLong() &&
                now - storedAt < lifetime
        val result = if (current) RetrievalCodec.decode(row.payload) else null
        if (result == null) {
            context.queries.retrievalDelete(row.id)
        } else {
            context.queries.retrievalTouch(now.toEpochMilliseconds(), row.id)
        }
        return result?.let { CachedResult(it, storedAt) }
    }

    /** Keeps what [adapter] gave for [lookupKey]; see [put]. */
    fun put(
        adapter: SourceAdapter,
        lookupKey: String,
        result: SourceResult,
    ): Boolean = put(adapter.id, lookupKey, adapter.extractionVersion, result)

    /**
     * Keeps [result], replacing any entry for [source] and [lookupKey], and evicts down to the size
     * limit. Returns whether it was kept: not for a source with no lifetime, nor for an
     * [Outcome.Unreachable] or [Outcome.SourceOff] result.
     */
    fun put(
        source: SourceId,
        lookupKey: String,
        extractionVersion: Int,
        result: SourceResult,
    ): Boolean {
        val keep =
            context.settings().cacheLifetimes.containsKey(source) &&
                result.outcome != Outcome.Unreachable &&
                result.outcome != Outcome.SourceOff
        if (keep) {
            val payload = RetrievalCodec.encode(result)
            val now = context.clock.now().toEpochMilliseconds()
            context.queries.transaction {
                context.queries.retrievalPut(
                    source = source.tag,
                    lookup_key = lookupKey,
                    format = RetrievalCodec.FORMAT.toLong(),
                    extraction = extractionVersion.toLong(),
                    payload = payload,
                    stored_at = now,
                    used_at = now,
                    size_bytes = source.tag.utf8Size() + lookupKey.utf8Size() + payload.utf8Size(),
                )
                context.afterWrite()
            }
        }
        return keep
    }
}

/** A kept [result], and when it was stored. */
data class CachedResult(
    val result: SourceResult,
    val storedAt: Instant,
)
