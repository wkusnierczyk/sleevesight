package io.github.wkusnierczyk.sleevesight.store

import app.cash.sqldelight.db.SqlDriver
import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import io.github.wkusnierczyk.sleevesight.store.db.CacheDatabase
import io.github.wkusnierczyk.sleevesight.store.db.CacheQueries
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The caches (`design/architecture.md`, *Cache and history*), over a database the caller opens:
 * [driver] is SQLite through JDBC in `:cli` and the tests, the Android driver in `:app`, with
 * [CacheDatabase.Schema] created on it.
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
 * Calls are blocking database calls: the app makes them off the main thread.
 */
class Caches(
    driver: SqlDriver,
    private val settings: () -> Settings,
    private val clock: Clock = Clock.System,
) {
    private val queries: CacheQueries = CacheDatabase(driver).cacheQueries
    private val context = CacheContext(queries, settings, clock, ::enforceLimit)

    val retrieval: RetrievalCache = RetrievalCache(context)
    val artists: ArtistCache = ArtistCache(context)
    val synthesis: SynthesisCache = SynthesisCache(context)

    /**
     * Deletes what reads would no longer return: retrieval entries past their source's lifetime
     * (also those of a source that no longer has one, or in an old encoding) and artist entries past
     * theirs, as of [now]. Then evicts down to the size limit, which may have been lowered since.
     * Reads never return an expired entry either way; this frees the space. `:cli` calls it at
     * start, and the app on start. Returns how many entries it deleted.
     */
    fun purgeExpired(now: Instant = clock.now()): Long =
        queries.transactionWithResult {
            val current = settings()
            var deleted = 0L
            for ((source, lifetime) in current.cacheLifetimes) {
                deleted += queries.retrievalPurgeSource(source.tag, (now - lifetime).toEpochMilliseconds()).value
            }
            deleted += queries.retrievalPurgeOtherSources(current.cacheLifetimes.keys.map { it.tag }).value
            deleted += queries.retrievalPurgeFormat(RetrievalCodec.FORMAT.toLong()).value
            deleted += queries.artistPurge((now - current.artistCacheLifetime).toEpochMilliseconds()).value
            deleted + enforceLimit()
        }

    /** The bytes of keys and values held, which [Settings.cacheSizeLimit] limits. */
    fun sizeBytes(): Long = stats().bytes

    /**
     * Entries and bytes per cache, for the diagnostics screen and the gate's measurements (#46).
     * The bytes are those of the keys and values, not of the database file, which adds SQLite's
     * page overhead and does not shrink until vacuumed.
     */
    fun stats(): CacheStats {
        val tables = queries.tableSizes().executeAsList().associate { it.name to TableStats(it.rows, it.bytes ?: 0) }
        return CacheStats(
            retrieval = tables.getValue(RETRIEVAL),
            artists = tables.getValue(ARTIST),
            synthesis = tables.getValue(SYNTHESIS),
        )
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
)

/** The size a key or value counts for: its UTF-8 bytes. */
internal fun String.utf8Size(): Long = encodeToByteArray().size.toLong()
