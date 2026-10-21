package app.sleevesight.store

import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.KeptPart
import kotlin.time.Instant

/**
 * What sources said about an artist, per [ArtistPart] and artist key (#238), for every track that
 * credits the artist: the store behind [app.sleevesight.core.sources.ArtistResults]. The rules are
 * the retrieval cache's ([RetrievalCache]): an entry lives for the shortest lifetime in
 * [Settings.cacheLifetimes] (D9) among the part's sources, measured at each read, and is not kept
 * when one of them has none; an entry under another version of its part is a miss, deleted when
 * read, replaced or evicted; the size limit counts it, and the least recently used go first,
 * with the retrieval and synthesis entries. The key is the caller's (a MusicBrainz id, or a name
 * the adapter folded); the cache does not normalize it, and the value is the adapter's encoding,
 * which it does not read.
 */
class ArtistResultCache internal constructor(
    private val context: CacheContext,
) {
    /**
     * The value kept for [key] under [part], if kept under [part]'s version and within its lifetime,
     * with the time it stops being fresh under the current lifetime.
     */
    fun get(
        part: ArtistPart,
        key: String,
    ): KeptPart? {
        val row = context.queries.artistResultGet(part.name, key).executeAsOneOrNull() ?: return null
        val now = context.clock.now()
        val lifetime = shortestLifetime(sourceTags(row.sources), context.settings())
        val freshUntil = lifetime?.let { Instant.fromEpochMilliseconds(row.stored_at) + it }
        val current = freshUntil != null && row.version == part.version.toLong() && now < freshUntil
        if (current) {
            context.queries.artistResultTouch(now.toEpochMilliseconds(), row.id)
        } else {
            // Only the row this read saw: one written since has another id.
            context.queries.artistResultDeleteSeen(row.id, row.stored_at)
        }
        return if (current && freshUntil != null) KeptPart(row.payload, freshUntil) else null
    }

    /**
     * Keeps [value] for [key] under [part], replacing what was kept, and evicts down to the size
     * limit. Returns whether it was kept: not when one of the part's sources has no lifetime, nor
     * when the entry alone is larger than the limit.
     */
    fun put(
        part: ArtistPart,
        key: String,
        value: String,
    ): Boolean {
        val sources = part.sources.sortedBy { it.ordinal }
        val sourcesColumn = sources.joinToString(" ", prefix = " ", postfix = " ") { it.tag }
        val size = part.name.utf8Size() + key.utf8Size() + sourcesColumn.utf8Size() + value.utf8Size()
        val keep = shortestLifetime(sources, context.settings()) != null && context.fits(size)
        if (keep) {
            val now = context.clock.now().toEpochMilliseconds()
            context.queries.transaction {
                context.queries.artistResultRemoveKey(part.name, key)
                context.queries.artistResultInsert(
                    part = part.name,
                    artist_key = key,
                    sources = sourcesColumn,
                    version = part.version.toLong(),
                    payload = value,
                    stored_at = now,
                    used_at = now,
                    size_bytes = size,
                )
                context.afterWrite()
            }
        }
        return keep
    }
}
