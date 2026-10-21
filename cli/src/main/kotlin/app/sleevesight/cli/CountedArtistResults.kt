package app.sleevesight.cli

import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.KeptPart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The run's artist cache (#238), [delegate], counting per part the reads that found an entry and
 * those that did not, for the report: each hit is an artist-level lookup a source was not asked
 * again, so the saving can be measured on the regression tracks. A read that throws counts as
 * neither.
 */
class CountedArtistResults(
    private val delegate: ArtistResults,
) : ArtistResults {
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val misses = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun get(
        part: ArtistPart,
        key: String,
    ): KeptPart? =
        delegate.get(part, key).also { found ->
            (if (found == null) misses else hits).computeIfAbsent(part.name) { AtomicInteger() }.incrementAndGet()
        }

    override suspend fun keep(
        part: ArtistPart,
        key: String,
        value: String,
    ) = delegate.keep(part, key, value)

    /** The reads so far, per part name, in name order. */
    fun counts(): List<ArtistCacheCount> =
        (hits.keys + misses.keys).sorted().map { part ->
            ArtistCacheCount(part, hits[part]?.get() ?: 0, misses[part]?.get() ?: 0)
        }
}

/** The reads of one part of the artist cache: [hits] found an entry, [misses] did not. */
data class ArtistCacheCount(
    val part: String,
    val hits: Int,
    val misses: Int,
)
