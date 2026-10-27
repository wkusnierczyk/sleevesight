package app.sleevesight.store

import app.cash.sqldelight.db.SqlDriver
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.store.db.CacheDatabase
import app.sleevesight.store.db.CacheQueries
import kotlin.time.Clock
import kotlin.time.Duration
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
 * - [artistResults]: what sources said about an artist, per artist rather than per track (#238),
 *   for the lifetime of the sources each depends on ([Settings.cacheLifetimes], D9).
 * - [synthesis]: written insights and cards by the full synthesis key (R2, D14), until evicted.
 *
 * [settings] is read on every call, so a changed lifetime or size limit applies at once. Time comes
 * from [clock] alone. Past [Settings.cacheSizeLimit], the least recently used retrieval,
 * artist-level and synthesis entries are evicted first, artist resolution entries (small and
 * long-lived) only after them.
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
    val artistResults: ArtistResultCache = ArtistResultCache(context)
    val synthesis: SynthesisCache = SynthesisCache(context)

    /**
     * Deletes the expired entries, as of [now]: retrieval entries past the shortest lifetime of the
     * sources they hold lines from or consulted (also those holding a source that no longer has
     * one, those of a source the app no longer knows, and those in an old encoding), artist-level
     * results past the shortest lifetime of the sources they depend on (with the same two cases),
     * and artist resolution entries past theirs. Then evicts down to the size limit, which may
     * have been lowered since. A genre's reason (#523) written longer ago than *Keep history for* is
     * expired too. Reads never return an expired entry either way; this frees the space. An entry
     * kept under an adapter's older extraction version, or an older version of its artist-level
     * part, is not expired: it is deleted when read, replaced, or evicted. `:cli`
     * calls this at start, and the app on start. Returns how many entries it deleted.
     */
    fun purgeExpired(now: Instant = clock.now()): Long =
        queries.transactionWithResult {
            val current = settings()
            var deleted = 0L
            for (source in SourceId.entries) {
                val cutoff = current.cacheLifetimes[source]?.let { (now - it).toEpochMilliseconds() } ?: Long.MAX_VALUE
                deleted += queries.retrievalPurgeSource(source.tag, cutoff).value
                deleted += queries.artistResultPurgeSource(source.tag, cutoff).value
            }
            deleted += purgeUnknownSources()
            deleted += queries.retrievalPurgeFormat(RetrievalCodec.FORMAT.toLong()).value
            deleted += queries.artistPurge((now - current.artistCacheLifetime).toEpochMilliseconds()).value
            for (kind in CardKind.entries) {
                val lifetime = SynthesisCache.lifetimeOf(kind, current) ?: continue
                deleted += queries.synthesisPurgeKind(kind.key, (now - lifetime).toEpochMilliseconds()).value
            }
            deleted + enforceLimit()
        }

    /**
     * Deletes everything kept about artists, for *Keep what sources said about each artist* switched
     * off (#420): the artist-level results, the artist resolutions, and every retrieval entry not
     * marked as about the track alone ([RetrievalCodec.aboutArtists]), those in an older encoding,
     * which cannot say so, included. Returns how many entries it deleted.
     */
    fun clearArtists(): Long =
        queries.transactionWithResult {
            queries.artistResultDeleteAll().value + queries.artistDeleteAll().value +
                queries.retrievalPurgeFormat(RetrievalCodec.FORMAT.toLong()).value +
                queries.retrievalDeleteAboutArtists(RetrievalCodec.TRACK_ONLY_MARKER).value
        }

    /**
     * Deletes everything the caches keep, for *Clear history* (#547): the retrieval entries, the
     * artist resolutions, the artist-level results and the written insights and cards, in one
     * transaction, so a failure leaves them as they were. Returns how many entries it deleted.
     */
    fun clearAll(): Long =
        queries.transactionWithResult {
            queries.retrievalDeleteAll().value + queries.artistDeleteAll().value +
                queries.artistResultDeleteAll().value + queries.synthesisDeleteAll().value
        }

    // Rows holding a source tag the app no longer knows, as the adapter's source or among the
    // sources of its lines or consulted (or, for an artist-level result, depended on); a read
    // treats them as misses. Reads the small `sources` column of every row, once per purge.
    private fun purgeUnknownSources(): Long {
        val known = SourceId.entries.map { it.tag }.toSet()

        fun unknown(sources: String) = sources.split(' ').any { it.isNotEmpty() && it !in known }
        val retrieval =
            queries
                .retrievalSources()
                .executeAsList()
                .filter { unknown(it.sources) }
                .sumOf { queries.retrievalDeleteSeen(it.id, it.stored_at).value }
        val artistResults =
            queries
                .artistResultSources()
                .executeAsList()
                .filter { unknown(it.sources) }
                .sumOf { queries.artistResultDeleteSeen(it.id, it.stored_at).value }
        return retrieval + artistResults
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
            artistResults = tables.getValue(ARTIST_RESULT),
        )
    }

    // [stats] counted from the rows rather than the running totals, for the tests.
    internal fun countedStats(): CacheStats {
        val tables =
            queries.tableSizesCounted().executeAsList().associate { it.name to TableStats(it.entries, it.bytes ?: 0) }
        return CacheStats(
            tables.getValue(RETRIEVAL),
            tables.getValue(ARTIST),
            tables.getValue(SYNTHESIS),
            tables.getValue(ARTIST_RESULT),
        )
    }

    // Evicts the least recently used entries until the caches fit the limit: retrieval, artist-level
    // and synthesis first, artist resolutions only when those are gone. Returns how many it evicted.
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
                    ARTIST_RESULT -> Evictable(row.size_bytes) { queries.artistResultDelete(row.id) }
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
        const val ARTIST_RESULT = "artist_result"

        const val EVICTION_BATCH = 64L
    }
}

/**
 * What the caches hold: [artists] the artist resolutions, [artistResults] the artist-level source
 * results (#238). [bytes] is what [Settings.cacheSizeLimit] limits.
 */
data class CacheStats(
    val retrieval: TableStats,
    val artists: TableStats,
    val synthesis: TableStats,
    val artistResults: TableStats,
) {
    val entries: Long get() = retrieval.entries + artists.entries + synthesis.entries + artistResults.entries
    val bytes: Long get() = retrieval.bytes + artists.bytes + synthesis.bytes + artistResults.bytes
}

/** One cache's entries and the bytes of their keys and values. */
data class TableStats(
    val entries: Long,
    val bytes: Long,
)

/** What the caches share. [afterWrite] evicts down to the size limit. */
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

/** The sources of a `sources` column (` W WD `), null for a tag the app no longer knows. */
internal fun sourceTags(column: String): List<SourceId?> =
    column.split(' ').filter { it.isNotEmpty() }.map { SourceId.ofTag(it) }

/** The shortest lifetime among [sources], or null when one has none (or is unknown). */
internal fun shortestLifetime(
    sources: List<SourceId?>,
    settings: Settings,
): Duration? {
    val lifetimes = sources.map { source -> source?.let { settings.cacheLifetimes[it] } }
    return if (lifetimes.any { it == null }) null else lifetimes.filterNotNull().minOrNull()
}
