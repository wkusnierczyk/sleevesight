package io.github.wkusnierczyk.sleevesight.store

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What source lookups gave, per source and track ([keyOf]), kept for the shortest lifetime in
 * [Settings.cacheLifetimes] (D9) among the sources the result holds lines from: the Wikipedia
 * adapter's result also holds Wikidata's lines, and lives no longer than Wikidata's lifetime.
 *
 * It keeps the [SourceResult] the pipeline uses (outcome, evidence lines, unverified lines,
 * rejections, latency), never the raw response: that keeps it small, and keeps text the app does
 * not show (the rest of an article, a whole bio) out of storage. So a cached result depends on the
 * adapter's extraction rules too: each entry carries the adapter's [SourceAdapter.extractionVersion],
 * and one written under another version is a miss.
 *
 * Not kept: a result that is [Outcome.Unreachable] (a network error is not an answer) or
 * [Outcome.SourceOff], anything holding a source with no lifetime (the pseudo-sources), and an
 * entry larger than the whole size limit. Never kept: web search's results, or any result holding a
 * web line, whatever the settings: Exa's terms (§4.2(a)) forbid storing what its service returns
 * (`design/design.md`, *Web search provider (D11)*). The orchestration (#44) also does not keep a result
 * verified against a resolution that could not be made, since it would stay unverified after
 * MusicBrainz is back.
 */
class RetrievalCache internal constructor(
    private val context: CacheContext,
    private val normalizer: Normalizer,
) {
    /**
     * The result [adapter] gave for [track], if kept, current and within its lifetime. The key is
     * [keyOf]: the track's lookup key, its credited artists and the work it is from, which the
     * adapters read besides the title and primary artist.
     */
    fun get(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
    ): CachedResult? = get(adapter.id, keyOf(track, duration), adapter.extractionVersion)

    /**
     * The result [source]'s adapter, at [extractionVersion], gave for [key], if kept, current and
     * within its lifetime. [key] must cover everything the adapter's lookup reads from the track
     * ([keyOf] does): two tracks that share a key share an entry.
     */
    fun get(
        source: SourceId,
        key: String,
        extractionVersion: Int,
    ): CachedResult? {
        val row = context.queries.retrievalGet(source.tag, key).executeAsOneOrNull() ?: return null
        val now = context.clock.now()
        val storedAt = Instant.fromEpochMilliseconds(row.stored_at)
        val lifetime = lifetimeOf(tagsOf(row.sources), context.settings())
        val current =
            lifetime != null &&
                row.format == RetrievalCodec.FORMAT.toLong() &&
                row.extraction == extractionVersion.toLong() &&
                now - storedAt < lifetime
        val result = if (current) RetrievalCodec.decode(row.payload) else null
        if (result == null) {
            // Only the row this read saw: one written since has another id.
            context.queries.retrievalDeleteSeen(row.id, row.stored_at)
        } else {
            context.queries.retrievalTouch(now.toEpochMilliseconds(), row.id)
        }
        return result?.let { CachedResult(it, storedAt) }
    }

    /** Keeps what [adapter] gave for [track], played at [duration]; see [put]. */
    fun put(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ): Boolean = put(adapter.id, keyOf(track, duration), adapter.extractionVersion, result, adapter.consults)

    /**
     * Keeps [result], replacing any entry for [source] and [key], and evicts down to the size
     * limit. The entry lives for the shortest lifetime among [consults] (the sources the adapter
     * asked, [SourceAdapter.consults]: a rejection by Wikidata's identity check has no Wikidata line
     * but depends on Wikidata) and the sources of its lines. Returns whether it was kept: not when
     * one of them has no lifetime, nor for an [Outcome.Unreachable] or [Outcome.SourceOff] result,
     * nor when it is larger than the limit.
     */
    fun put(
        source: SourceId,
        key: String,
        extractionVersion: Int,
        result: SourceResult,
        consults: Set<SourceId> = setOf(source),
    ): Boolean {
        val lineSources = (result.evidence + result.unverified).map { it.source }
        val sources = (listOf(source) + consults + lineSources).distinct()
        val payload = RetrievalCodec.encode(result)
        val sourcesColumn = sources.joinToString(" ", prefix = " ", postfix = " ") { it.tag }
        val size = source.tag.utf8Size() + key.utf8Size() + sourcesColumn.utf8Size() + payload.utf8Size()
        val keep =
            SourceId.WEB !in sources &&
                lifetimeOf(sources, context.settings()) != null &&
                result.outcome != Outcome.Unreachable &&
                result.outcome != Outcome.SourceOff &&
                context.fits(size)
        if (keep) {
            val now = context.clock.now().toEpochMilliseconds()
            context.queries.transaction {
                context.queries.retrievalRemoveKey(source.tag, key)
                context.queries.retrievalInsert(
                    source = source.tag,
                    lookup_key = key,
                    sources = sourcesColumn,
                    format = RetrievalCodec.FORMAT.toLong(),
                    extraction = extractionVersion.toLong(),
                    payload = payload,
                    stored_at = now,
                    used_at = now,
                    size_bytes = size,
                )
                context.afterWrite()
            }
        }
        return keep
    }

    /**
     * The retrieval key of [track]: its [NormalizedTrack.lookupKey] (primary artist and title), its
     * credited artists, sorted, and the work it is from, each folded as the lookup key is
     * ([Normalizer.foldForKey]), so equivalent spellings (NFC and NFD, any case) give one key. The
     * adapters read the artists (each is looked up, and verified) and the work (web search filters
     * on it), so "Song (feat. X)" and "Song" get entries of their own. Then the length the player
     * reports, [duration], in whole seconds, or `-` when it reports none: resolution chooses the
     * recording by its length, so the same song at another length may resolve to another recording.
     * Each part is prefixed with its length, so no two different combinations of parts encode alike.
     */
    fun keyOf(
        track: NormalizedTrack,
        duration: Duration?,
    ): String {
        val parts =
            listOf(track.lookupKey) + track.artists.map(normalizer::foldForKey).sorted() +
                listOf(track.from?.let(normalizer::foldForKey), duration?.let { "${it.inWholeSeconds}s" })
        return parts.joinToString("") { part -> part?.let { "${it.length}:$it" } ?: "-" }
    }

    private companion object {
        private fun tagsOf(column: String): List<SourceId?> =
            column.split(' ').filter { it.isNotEmpty() }.map { SourceId.ofTag(it) }

        // The shortest lifetime among [sources], or null when one has none (or is unknown).
        private fun lifetimeOf(
            sources: List<SourceId?>,
            settings: Settings,
        ): Duration? {
            val lifetimes = sources.map { source -> source?.let { settings.cacheLifetimes[it] } }
            return if (lifetimes.any { it == null }) null else lifetimes.filterNotNull().minOrNull()
        }
    }
}

/** A kept [result], and when it was stored. */
data class CachedResult(
    val result: SourceResult,
    val storedAt: Instant,
)
