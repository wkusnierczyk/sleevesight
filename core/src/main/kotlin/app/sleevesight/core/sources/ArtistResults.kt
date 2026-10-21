package app.sleevesight.core.sources

import app.sleevesight.core.model.SourceId
import kotlin.time.Instant

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
 *
 * A kept value comes with the time it stops being fresh ([KeptPart.freshUntil]). A track result
 * built from it carries the earliest such time ([SourceResult.freshUntil]), so the retrieval cache
 * does not keep a reused part past its own lifetime. A caller that keeps values in memory as well
 * keeps them no longer than that.
 */
interface ArtistResults {
    /**
     * Whether this keeps anything: false for [None], and for a wrapper of it. A caller that would
     * otherwise keep values in memory does so only when this is false, so a value is kept in one
     * place, never in memory past its lifetime here.
     */
    val persists: Boolean get() = true

    /** The value kept for [key] under [part], if kept under [part]'s version and within its lifetime. */
    suspend fun get(
        part: ArtistPart,
        key: String,
    ): KeptPart?

    /** Keeps [value] for [key] under [part], replacing what was kept; the store may decline (the size limit). */
    suspend fun keep(
        part: ArtistPart,
        key: String,
        value: String,
    )

    /** Keeps nothing: every lookup asks afresh. */
    object None : ArtistResults {
        override val persists: Boolean = false

        override suspend fun get(
            part: ArtistPart,
            key: String,
        ): KeptPart? = null

        override suspend fun keep(
            part: ArtistPart,
            key: String,
            value: String,
        ) = Unit
    }
}

/** A kept [value], and the time it stops being fresh: when it was kept, plus its current lifetime. */
data class KeptPart(
    val value: String,
    val freshUntil: Instant,
)

/**
 * One kind of artist-level result an adapter keeps in [ArtistResults].
 *
 * @property name the part's own name, unique among parts, the store's key with the artist's key:
 *   `lastfm.artist`, say. Kept with each entry, so it is never renamed.
 * @property extraction the [SourceAdapter.extractionVersion] of the adapter that makes the value:
 *   the value embeds its extraction rules (which sentences, which verdict), so raising the
 *   adapter's version misses its kept parts too.
 * @property encoding the version of the part's own encoding, raised when it changes; from 1 to
 *   [ENCODINGS] - 1.
 * @property sources the sources the value depends on: it is kept for the shortest of their
 *   lifetimes ([app.sleevesight.core.settings.Settings.cacheLifetimes], D9), and not at all when
 *   one has none.
 */
data class ArtistPart(
    val name: String,
    val extraction: Int,
    val encoding: Int,
    val sources: Set<SourceId>,
) {
    init {
        require(name.isNotEmpty() && ' ' !in name) { "A part's name is empty or holds a space" }
        require(extraction >= 1 && encoding in 1 until ENCODINGS) { "A part's versions are out of range" }
        require(sources.isNotEmpty()) { "A part depends on at least one source" }
    }

    /**
     * The one version the store keeps with each entry, from [extraction] and [encoding]: an entry
     * kept under another is a miss.
     */
    val version: Int get() = extraction * ENCODINGS + encoding

    companion object {
        /** Encodings per extraction version, so the two make one version. */
        const val ENCODINGS: Int = 1000
    }
}
