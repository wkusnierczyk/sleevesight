package app.sleevesight.core.sources

import app.sleevesight.core.evidence.EvidenceDraft

/**
 * The evidence gathered for an album, not a track (#522; `design/architecture.md`, *Cache and
 * history*), for the album's insight under *Key insights*: what MusicBrainz's release group, its
 * Wikidata item, its English Wikipedia article and Last.fm's album (#885) say about the album, and
 * the pages a web search for it found ([searchWeb]). An interface here, implemented in `:sources`
 * (`AlbumSource`), which `:core` cannot see.
 *
 * What it answers depends on the album alone ([AlbumQuery]), never on the track playing, so the
 * album's insight is found again with no call on each of its tracks: the same lines, the same
 * fingerprint. An implementation keeps what it found per album (the artist-level table, #238), and
 * does not throw, but for cancellation: a source that fails is [AlbumFound.unreachable], and what
 * the others found is still given.
 *
 * The web search is paid, so [lookup] never makes it: it gives the web's lines only when an earlier
 * search kept them, and says when one is due ([AlbumFound.webDue]). [searchWeb] makes it, for a
 * write of the album's insight only, and keeps what it found, so a later [lookup] gives the same
 * lines, and the insight written from them is found again by its fingerprint.
 */
fun interface AlbumLookup {
    /**
     * What the sources [query] may ask say about the album it names, with the web's lines an
     * earlier search kept ([searchWeb]); never a web search of its own.
     */
    suspend fun lookup(query: AlbumQuery): AlbumFound

    /**
     * The album's web lines (#885), for a write of its insight only: those kept from an earlier
     * search, or found by a search made now, a paid one, and kept; null when no search may be made
     * ([AlbumQuery.web] off, or no engine) or it failed, when nothing is kept. None by default.
     */
    suspend fun searchWeb(query: AlbumQuery): List<EvidenceDraft>? = null
}

/**
 * An album to look up: its [title], the player's name for it; its [artist], the track's primary
 * artist; and its MusicBrainz release group, [group] (the group's id, or the release's own, as
 * `ReleaseDate.group` has it), null when the album's name names none (or MusicBrainz was not
 * reached), when it is looked up by its name. [musicBrainz], [wikipedia], [lastFm] and [web] say
 * which sources may be asked, as *Sources* has them switched (Wikipedia with Wikidata,
 * `SourceSwitch.WIKIPEDIA`; the web with an engine switched on, `Settings.isEnabled`); Last.fm and
 * the web also need their keys, which the implementation is built with.
 */
data class AlbumQuery(
    val title: String,
    val artist: String,
    val group: String?,
    val musicBrainz: Boolean = true,
    val wikipedia: Boolean = true,
    val lastFm: Boolean = true,
    val web: Boolean = true,
)

/**
 * The album's lines ([drafts]), whether a source could not be asked ([unreachable]), and whether a
 * web search for the album is due before its insight is written ([webDue]: it may be asked, and no
 * search was kept), which [AlbumLookup.searchWeb] makes.
 */
data class AlbumFound(
    val drafts: List<EvidenceDraft>,
    val unreachable: Boolean = false,
    val webDue: Boolean = false,
) {
    companion object {
        /** Nothing found, and nothing failed. */
        val NONE: AlbumFound = AlbumFound(emptyList())
    }
}
