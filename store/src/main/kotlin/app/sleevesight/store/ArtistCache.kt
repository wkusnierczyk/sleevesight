package app.sleevesight.store

import kotlin.time.Instant

/**
 * Credited artists' MusicBrainz ids by normalized name (architecture, *Entity resolution*, step 3),
 * kept for [app.sleevesight.core.settings.Settings.artistCacheLifetime]. The
 * name is the caller's normalized form (lowercased and accent-folded, as in
 * [app.sleevesight.core.model.NormalizedTrack.lookupKey]); the cache does not
 * normalize. A finding of no artist, or of several, is kept as well, so it is not asked again.
 */
class ArtistCache internal constructor(
    private val context: CacheContext,
) {
    /** What is kept for [name], if within its lifetime. */
    fun get(name: String): ArtistResolution? {
        val row = context.queries.artistGet(name).executeAsOneOrNull() ?: return null
        val now = context.clock.now()
        val fresh = now - Instant.fromEpochMilliseconds(row.stored_at) < context.settings().artistCacheLifetime
        val resolution = if (fresh) resolution(row.state, row.mbid) else null
        if (resolution == null) {
            // Only the row this read saw: one written since has another id.
            context.queries.artistDeleteSeen(row.id, row.stored_at)
        } else {
            context.queries.artistTouch(now.toEpochMilliseconds(), row.id)
        }
        return resolution
    }

    /**
     * Keeps [resolution] for [name], replacing what was kept, and evicts down to the size limit.
     * Returns whether it was kept: not when it alone is larger than the limit.
     */
    fun put(
        name: String,
        resolution: ArtistResolution,
    ): Boolean {
        val (state, mbid) =
            when (resolution) {
                is ArtistResolution.Resolved -> RESOLVED to resolution.mbid
                ArtistResolution.None -> NONE to null
                is ArtistResolution.Ambiguous -> AMBIGUOUS to resolution.candidates.joinToString(" ")
            }
        val size = name.utf8Size() + state.utf8Size() + (mbid?.utf8Size() ?: 0)
        val keep = context.fits(size)
        if (keep) {
            val now = context.clock.now().toEpochMilliseconds()
            context.queries.transaction {
                context.queries.artistRemoveKey(name)
                context.queries.artistInsert(
                    name = name,
                    state = state,
                    mbid = mbid,
                    stored_at = now,
                    used_at = now,
                    size_bytes = size,
                )
                context.afterWrite()
            }
        }
        return keep
    }

    private fun resolution(
        state: String,
        mbid: String?,
    ): ArtistResolution? =
        when (state) {
            RESOLVED -> mbid?.let { ArtistResolution.Resolved(it) }
            NONE -> ArtistResolution.None
            AMBIGUOUS -> ArtistResolution.Ambiguous(mbid.orEmpty().split(" ").filter { it.isNotEmpty() })
            else -> null
        }

    private companion object {
        const val RESOLVED = "resolved"
        const val NONE = "none"
        const val AMBIGUOUS = "ambiguous"
    }
}

/** What MusicBrainz says a credited artist's name refers to. */
sealed interface ArtistResolution {
    /** One artist, [mbid]. */
    data class Resolved(
        val mbid: String,
    ) : ArtistResolution

    /** No artist. */
    data object None : ArtistResolution

    /** Several artists by that name, [candidates], so none is chosen. */
    data class Ambiguous(
        val candidates: List<String>,
    ) : ArtistResolution {
        init {
            require(candidates.none { it.isEmpty() || ' ' in it }) { "A candidate id is empty or holds a space" }
        }
    }
}
