package app.sleevesight.store

import app.cash.sqldelight.db.SqlDriver
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.db.CacheDatabase
import app.sleevesight.store.db.CacheQueries
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The caches (`design/architecture.md`, *Cache and history*), over a database the caller opens:
 * [driver] is SQLite through JDBC in `:cli` and the tests, the Android driver in `:app`, with
 * [CacheDatabase.Schema] created on it. [normalizer] folds the parts of the retrieval key
 * ([RetrievalCache.keyOf]) as the lookup key is folded.
 *
 * - [retrieval]: what each source lookup gave, per source and lookup key, for the source's
 *   lifetime ([Settings.cacheLifetimes], D9).
 * - [artists]: each credited artist's MusicBrainz id by normalized name, for
 *   [Settings.artistCacheLifetime].
 * - [synthesis]: written insights and cards by the full synthesis key (R2, D14), until evicted.
 *
 * [settings] is read on every call, so a changed lifetime or size limit applies at once. Time comes
 * from [clock] alone. Past [Settings.cacheSizeLimit], the least recently used retrieval and
 * synthesis entries are evicted first, artist entries (small and long-lived) only after them.
 *
 * Calls are blocking database calls: the app makes them off the main thread. Concurrent calls are
 * safe with a file-backed database (the JDBC driver on a file, or the Android driver), each write a
 * transaction of its own; not with the JDBC driver's in-memory database, which shares one
 * connection between threads and is for single-threaded tests only.
 */
class Caches(
    driver: SqlDriver,
    private val settings: () -> Settings,
    normalizer: Normalizer,
    private val clock: Clock = Clock.System,
) {
    internal val queries: CacheQueries = CacheDatabase(driver).cacheQueries
    private val context = CacheContext(queries, settings, clock, ::enforceLimit)

    val retrieval: RetrievalCache = RetrievalCache(context, normalizer)
    val artists: ArtistCache = ArtistCache(context)
    val synthesis: SynthesisCache = SynthesisCache(context)

    /**
     * Deletes the expired entries, as of [now]: retrieval entries past the shortest lifetime of the
     * sources they hold lines from or consulted (also those holding a source that no longer has
     * one, those of a source the app no longer knows, and those in an old encoding) and artist
     * entries past theirs. Then evicts down to the size limit, which may have been lowered since.
     * Reads never return an expired entry either way; this frees the
     * space. An entry kept under an adapter's older extraction version is not expired: it is
     * deleted when read, replaced, or evicted. `:cli` calls this at start, and the app on start.
     * Returns how many entries it deleted.
     */
    fun purgeExpired(now: Instant = clock.now()): Long =
        queries.transactionWithResult {
            val current = settings()
            var deleted = 0L
            for (source in SourceId.entries) {
                val cutoff = current.cacheLifetimes[source]?.let { (now - it).toEpochMilliseconds() } ?: Long.MAX_VALUE
                deleted += queries.retrievalPurgeSource(source.tag, cutoff).value
            }
            deleted += purgeUnknownSources()
            deleted += queries.retrievalPurgeFormat(RetrievalCodec.FORMAT.toLong()).value
            deleted += queries.artistPurge((now - current.artistCacheLifetime).toEpochMilliseconds()).value
            deleted + enforceLimit()
        }

    // Rows holding a source tag the app no longer knows, as the adapter's source or among the
    // sources of its lines or consulted; a read treats them as misses. Reads the small `sources`
    // column of every row, once per purge.
    private fun purgeUnknownSources(): Long {
        val known = SourceId.entries.map { it.tag }.toSet()
        return queries
            .retrievalSources()
            .executeAsList()
            .filter { row -> row.sources.split(' ').any { it.isNotEmpty() && it !in known } }
            .sumOf { queries.retrievalDeleteSeen(it.id, it.stored_at).value }
    }

    /** The bytes of keys and values held, which [Settings.cacheSizeLimit] limits. */
    fun sizeBytes(): Long = stats().bytes

    /**
     * Entries and bytes per cache, for the diagnostics screen and the gate's measurements (#46).
     * The bytes are those of the keys and values, not of the database file, which adds SQLite's
     * page overhead and does not shrink until vacuumed.
     */
    fun stats(): CacheStats {
        val tables = queries.tableSizes().executeAsList().associate { it.name to TableStats(it.entries, it.bytes) }
        return CacheStats(
            retrieval = tables.getValue(RETRIEVAL),
            artists = tables.getValue(ARTIST),
            synthesis = tables.getValue(SYNTHESIS),
        )
    }

    // [stats] counted from the rows rather than the running totals, for the tests.
    internal fun countedStats(): CacheStats {
        val tables =
            queries.tableSizesCounted().executeAsList().associate { it.name to TableStats(it.entries, it.bytes ?: 0) }
        return CacheStats(tables.getValue(RETRIEVAL), tables.getValue(ARTIST), tables.getValue(SYNTHESIS))
    }

    // Evicts the least recently used entries until the caches fit the limit: retrieval and
    // synthesis first, artists only when those are gone. Returns how many it evicted.
    private fun enforceLimit(): Long {
        var excess = sizeBytes() - settings().cacheSizeLimit
        var evicted = 0L
        while (excess > 0) {
            val batch = evictionBatch()
            if (batch.isEmpty()) break
            for (entry in batch) {
                if (excess <= 0) break
                entry.delete()
                excess -= entry.bytes
                evicted++
            }
        }
        return evicted
    }

    private fun evictionBatch(): List<Evictable> {
        val entries =
            queries.leastRecentlyUsed(EVICTION_BATCH).executeAsList().map { row ->
                when (row.name) {
                    RETRIEVAL -> Evictable(row.size_bytes) { queries.retrievalDelete(row.id) }
                    else -> Evictable(row.size_bytes) { queries.synthesisDelete(row.id) }
                }
            }
        return entries.ifEmpty {
            queries.leastRecentlyUsedArtists(EVICTION_BATCH).executeAsList().map { row ->
                Evictable(row.size_bytes) { queries.artistDelete(row.id) }
            }
        }
    }

    private class Evictable(
        val bytes: Long,
        val delete: () -> Unit,
    )

    private companion object {
        // The table names as `tableSizes` and `leastRecentlyUsed` give them.
        const val RETRIEVAL = "retrieval"
        const val ARTIST = "artist"
        const val SYNTHESIS = "synthesis"

        const val EVICTION_BATCH = 64L
    }
}

/** What the caches hold. [bytes] is what [Settings.cacheSizeLimit] limits. */
data class CacheStats(
    val retrieval: TableStats,
    val artists: TableStats,
    val synthesis: TableStats,
) {
    val entries: Long get() = retrieval.entries + artists.entries + synthesis.entries
    val bytes: Long get() = retrieval.bytes + artists.bytes + synthesis.bytes
}

/** One cache's entries and the bytes of their keys and values. */
data class TableStats(
    val entries: Long,
    val bytes: Long,
)

/** What the three caches share. [afterWrite] evicts down to the size limit. */
internal class CacheContext(
    val queries: CacheQueries,
    val settings: () -> Settings,
    val clock: Clock,
    val afterWrite: () -> Long,
) {
    /**
     * Whether an entry of [bytes] may be kept at all: one larger than the whole limit is not, rather
     * than evicting every other entry and then itself.
     */
    fun fits(bytes: Long): Boolean = bytes <= settings().cacheSizeLimit
}

/** The size a key or value counts for: its UTF-8 bytes. */
internal fun String.utf8Size(): Long = encodeToByteArray().size.toLong()
