package app.sleevesight.core.sources

import app.sleevesight.core.evidence.EvidenceDraft

/**
 * The evidence gathered for an album, not a track (#522; `design/architecture.md`, *Cache and
 * history*), for the album's insight under *Key insights*: what MusicBrainz's release group, its
 * Wikidata item and its English Wikipedia article say about the album. An interface here,
 * implemented in `:sources` (`AlbumSource`), which `:core` cannot see.
 *
 * What it answers depends on the album alone ([AlbumQuery]), never on the track playing, so the
 * album's insight is found again with no call on each of its tracks: the same lines, the same
 * fingerprint. An implementation keeps what it found per album (the artist-level table, #238), and
 * does not throw, but for cancellation: a source that fails is [AlbumFound.unreachable], and what
 * the others found is still given.
 */
fun interface AlbumLookup {
    /** What the sources [query] may ask say about the album it names. */
    suspend fun lookup(query: AlbumQuery): AlbumFound
}

/**
 * An album to look up: its [title], the player's name for it; its [artist], the track's primary
 * artist; and its MusicBrainz release group, [group] (the group's id, or the release's own, as
 * `ReleaseDate.group` has it), null when the album's name names none (or MusicBrainz was not
 * reached), when it is looked up by its name. [musicBrainz] and [wikipedia] say which sources may
 * be asked, as *Sources* has them switched (Wikipedia with Wikidata, `SourceSwitch.WIKIPEDIA`).
 */
data class AlbumQuery(
    val title: String,
    val artist: String,
    val group: String?,
    val musicBrainz: Boolean = true,
    val wikipedia: Boolean = true,
)

/** The album's lines ([drafts]), and whether a source could not be asked ([unreachable]). */
data class AlbumFound(
    val drafts: List<EvidenceDraft>,
    val unreachable: Boolean = false,
) {
    companion object {
        /** Nothing found, and nothing failed. */
        val NONE: AlbumFound = AlbumFound(emptyList())
    }
}
