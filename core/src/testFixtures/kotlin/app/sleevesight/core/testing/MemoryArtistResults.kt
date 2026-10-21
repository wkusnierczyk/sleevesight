package app.sleevesight.core.testing

import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.KeptPart
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * [ArtistResults] in a map, for the tests of what reads and keeps artist-level results: it keeps
 * everything under the part's name and version, for [lifetime] by [clock] (for ever by default),
 * and counts the reads that found an entry ([hits]) and those that did not ([misses]). Not
 * thread-safe: for single-threaded tests (`runTest`). The lifetimes per source, the size limit and
 * the database are `:store`'s, tested there.
 */
class MemoryArtistResults(
    private val clock: Clock = Clock.System,
    private val lifetime: Duration = Duration.INFINITE,
) : ArtistResults {
    private val entries = mutableMapOf<Triple<String, Int, String>, KeptPart>()

    /** The reads that found an entry, by part name. */
    val hits: MutableMap<String, Int> = mutableMapOf()

    /** The reads that found none, by part name. */
    val misses: MutableMap<String, Int> = mutableMapOf()

    /** The keys kept under [part], in no particular order. */
    fun keys(part: ArtistPart): Set<String> =
        entries.keys
            .filter { it.first == part.name && it.second == part.version }
            .map { it.third }
            .toSet()

    override suspend fun get(
        part: ArtistPart,
        key: String,
    ): KeptPart? =
        entries[Triple(part.name, part.version, key)]?.takeIf { clock.now() < it.freshUntil }.also { found ->
            val counts = if (found == null) misses else hits
            counts[part.name] = (counts[part.name] ?: 0) + 1
        }

    override suspend fun keep(
        part: ArtistPart,
        key: String,
        value: String,
    ) {
        val freshUntil = if (lifetime.isInfinite()) Instant.DISTANT_FUTURE else clock.now() + lifetime
        entries[Triple(part.name, part.version, key)] = KeptPart(value, freshUntil)
    }
}
