package app.sleevesight.core.sources

import app.sleevesight.core.model.SourceId

/**
 * What sources said about an artist, kept per artist rather than per track (#238;
 * `design/architecture.md`, *Cache and history*), so a second track by the same artist asks
 * nothing about the artist while the entry is fresh. Some lookups describe the artist, not the
 * track: Last.fm's `artist.getInfo` and `artist.getTopTags`, the artist's Wikipedia article (through
 * Wikidata, P434), MusicBrainz's artist tags. An adapter keeps their results here and builds each
 * track's result from its own track-level lookups and these parts; the retrieval cache then keeps
 * the track's result as before.
 *
 * An interface here, implemented by `:store` (`StoreArtistResults`), which `:core` and `:sources`
 * cannot see. Values are the adapter's own encoding, which the store does not read: [ArtistPart.version]
 * says which. Calls suspend, so an implementation over a blocking database moves them off the
 * caller's thread. An implementation does not throw, but for cancellation: a store that fails
 * misses, and keeps nothing, so the caller asks its source as it would with no cache.
 */
interface ArtistResults {
    /** The value kept for [key] under [part], if kept under [part]'s version and within its lifetime. */
    suspend fun get(
        part: ArtistPart,
        key: String,
    ): String?

    /** Keeps [value] for [key] under [part], replacing what was kept; the store may decline (the size limit). */
    suspend fun keep(
        part: ArtistPart,
        key: String,
        value: String,
    )

    /** Keeps nothing: every lookup asks afresh. */
    object None : ArtistResults {
        override suspend fun get(
            part: ArtistPart,
            key: String,
        ): String? = null

        override suspend fun keep(
            part: ArtistPart,
            key: String,
            value: String,
        ) = Unit
    }
}

/**
 * One kind of artist-level result an adapter keeps in [ArtistResults].
 *
 * @property name the part's own name, unique among parts, the store's key with the artist's key:
 *   `lastfm.artist`, say. Kept with each entry, so it is never renamed.
 * @property version the version of what the adapter makes of the answers and how it encodes them;
 *   an entry kept under another version is a miss. Raised when either changes, as
 *   [SourceAdapter.extractionVersion] is.
 * @property sources the sources the value depends on: it is kept for the shortest of their
 *   lifetimes ([app.sleevesight.core.settings.Settings.cacheLifetimes], D9), and not at all when
 *   one has none.
 */
data class ArtistPart(
    val name: String,
    val version: Int,
    val sources: Set<SourceId>,
) {
    init {
        require(name.isNotEmpty() && ' ' !in name) { "A part's name is empty or holds a space" }
        require(sources.isNotEmpty()) { "A part depends on at least one source" }
    }
}
