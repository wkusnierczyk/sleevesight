package app.sleevesight.core.sources

import app.sleevesight.core.evidence.EvidenceDraft

/**
 * The web lines about an artist the other sources say nothing about (#912; `design/architecture.md`,
 * *Cache and history*), for the artist's insight under *Key insights*: what a web search for the
 * artist found, kept per artist ([ArtistWebQuery]: by its MusicBrainz id, else by its name), so
 * the artist's insight written from them is found again by its fingerprint on each of the artist's
 * tracks. An interface here, implemented in `:sources` (`ArtistWebSource`), which `:core` cannot see.
 *
 * Every search is paid, so nothing here searches unless asked to, and only for a write of the
 * artist's insight (`ItemInsights`): [kept] gives what an earlier search kept; [search] makes the
 * app's own search (*AI web search* set to *Once*) and keeps what it found; [keep] keeps the pages
 * the AI service's own searches found (*Up to 3 times*). "Found nothing" is kept too, as an empty
 * list, so the search is not paid for again. An implementation does not throw, but for cancellation.
 */
interface ArtistWebLookup {
    /** The artist's web lines kept from an earlier search, none included; null when none is kept. */
    suspend fun kept(artist: ArtistWebQuery): List<EvidenceDraft>?

    /**
     * The artist's web lines for a write of its insight under *Once*: those kept, else those a search
     * made now finds, a paid one, kept, none included; null when it failed, and nothing is kept.
     * [sent] is told right before a request goes out to the engine, what is paid for, whatever comes
     * back: not for lines kept, nor for an artist whose name cannot be checked, which sends nothing.
     * [mayPay] is asked after any wait (the kept lines read, another write's search of the artist),
     * right before the request goes out: when it says no, nothing is sent or kept, and null is given.
     */
    suspend fun search(
        artist: ArtistWebQuery,
        sent: () -> Unit = {},
        mayPay: () -> Boolean = { true },
    ): List<EvidenceDraft>?

    /** Keeps [lines], what the AI service's own searches found for the artist (*Up to 3 times*), none included. */
    suspend fun keep(
        artist: ArtistWebQuery,
        lines: List<EvidenceDraft>,
    )

    /**
     * The line [page], found by the AI service's own search (*Up to 3 times*), gives for [artist], by
     * the rules the app's own search keeps a result by (#912's review: about the artist, not a
     * source the app reads itself, page boilerplate left out, cut as a web line is); null when it
     * gives none. As the page came by default.
     */
    fun pageLine(
        artist: ArtistWebQuery,
        page: WebPage,
    ): String? = page.text

    /**
     * The artist's web lines kept again, from now, when a card of the artist's insight was written,
     * "found nothing" included: they last as long as the last card written from them (*Keep history
     * for*), as an album's do (`AlbumLookup.renewWeb`). Nothing when none is kept.
     */
    suspend fun renew(artist: ArtistWebQuery)
}

/**
 * An artist to search the web for, or whose lines to keep (#912): its [name], as the artist's card
 * names it (`CardEntity.Artist`: the name the resolution credits, else the player's), which the
 * search sends; and its MusicBrainz id, [mbid], when the resolution gave one. Kept by the id, else
 * by the name, as the artist's card is keyed.
 */
data class ArtistWebQuery(
    val name: String,
    val mbid: String?,
)
