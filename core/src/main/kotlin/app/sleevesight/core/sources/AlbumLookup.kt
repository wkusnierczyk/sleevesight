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
     * Whether a web search for an album may be made here (#935): an engine with its key, which
     * [AlbumQuery.web] still has to ask for. Its evidence is gathered again when this changes
     * (`ItemInsights.searchesWeb`), as the artist's is when its lookup comes or goes. None by default.
     */
    val searchesWeb: Boolean get() = false

    /**
     * The album's web lines (#885), for a write of its insight only: those kept from an earlier
     * search, or found by a search made now, a paid one, and kept; null when no search may be made
     * ([AlbumQuery.web] off, or no engine) or it failed, when nothing is kept. [sent] is told each
     * time a request goes out to the engine, what is paid for, whatever comes back: not for lines
     * kept, nor for an album the search cannot check, which sends nothing. [mayPay] is asked after
     * any wait (the kept lines read, another write's search of the album), right before the request
     * goes out: when it says no, nothing is sent or kept, and null is given (#935, as
     * `ArtistWebLookup.search`). [keeping] is told the lines a search made now found, right before
     * they are kept (not lines kept earlier), so its caller claims the key they give before another
     * track can gather them (#935). None by default.
     */
    suspend fun searchWeb(
        query: AlbumQuery,
        mayPay: () -> Boolean = { true },
        keeping: (List<EvidenceDraft>) -> Unit = {},
        sent: () -> Unit = {},
    ): List<EvidenceDraft>? = null

    /**
     * The album's web lines kept again, from now (#885), when a card of the album's insight was
     * written from them, "found nothing" included: they are kept as long as the insight is, so they
     * last as long as the last card written from them, not only the first. Nothing when none is kept.
     * Nothing by default.
     */
    suspend fun renewWeb(query: AlbumQuery) = Unit
}

/**
 * An album to look up: its [title], the player's name for it; its [artist], the track's primary
 * artist; and its MusicBrainz release group, [group] (the group's id, or the release's own, as
 * `ReleaseDate.group` has it), null when the album's name names none (or MusicBrainz was not
 * reached), when it is looked up by its name. [musicBrainz], [wikipedia], [lastFm] and [web] say
 * which sources may be asked, as *Sources* has them switched (Wikipedia with Wikidata,
 * `SourceSwitch.WIKIPEDIA`; the web with an engine switched on, `Settings.isEnabled`); Last.fm and
 * the web also need their keys, which the implementation is built with.
 *
 * [performers]: on a track whose title names a credited composer (`NormalizedTrack.composer`,
 * #1130), the credited artists who perform (`NormalizedTrack.performers`), the composer and an
 * arranger the title names left out; empty otherwise. With them, a page or an item found by the
 * album's name is about this album only when it names one of them, not by [artist] (the composer)
 * alone: a classical album's title is shared by other performers' recordings of the same works
 * ("Mozart: Piano Concertos Nos. 20 & 23" by Buniatishvili, and by Shelley, 33 years earlier).
 * [credited]: on such a track, for a web search ([AlbumLookup.searchWeb]), every credited artist
 * in the player's order, the composer and an arranger the title names included, whom its request
 * names; empty otherwise, when it names [artist] alone. Only the request reads it: what is kept is
 * keyed by [performers].
 */
data class AlbumQuery(
    val title: String,
    val artist: String,
    val group: String?,
    val musicBrainz: Boolean = true,
    val wikipedia: Boolean = true,
    val lastFm: Boolean = true,
    val web: Boolean = true,
    val performers: List<String> = emptyList(),
    val credited: List<String> = emptyList(),
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
