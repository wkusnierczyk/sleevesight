package app.sleevesight.core.sources

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.language.Language
import app.sleevesight.core.model.NormalizedTrack
import kotlin.time.Instant

/**
 * The web lines about a song the other sources only list (#958; `design/architecture.md`, *Cache and
 * history*), for the song's insight under *Key insights*: what a web search for the song found, kept
 * per track, as [ArtistWebLookup] keeps an artist's. An interface here, implemented in `:sources`
 * (`SongWebSource`), which `:core` cannot see.
 *
 * Every search is paid, so nothing here searches unless asked to, and only for a write of the song's
 * insight (`ItemInsights`): [kept] gives what an earlier search kept; [search] makes the app's own
 * search (*AI web search* set to *Once*) and keeps what it found; [keep] keeps the pages the AI
 * service's own searches found (*Up to 3 times*). "Found nothing" is kept too, as an empty list, so
 * the search is not paid for again. An implementation does not throw, but for cancellation.
 */
interface SongWebLookup {
    /** The song's web lines kept from an earlier search, none included; null when none is kept. */
    suspend fun kept(song: SongWebQuery): List<EvidenceDraft>?

    /**
     * The song's web lines for a write of its insight under *Once*: those kept, else those the one
     * search made now finds, kept, none included; null when it failed, and nothing is kept. [sent],
     * [keeping] and [mayPay] as [ArtistWebLookup.search]'s.
     */
    suspend fun search(
        song: SongWebQuery,
        sent: () -> Unit = {},
        keeping: (List<EvidenceDraft>) -> Unit = {},
        mayPay: () -> Boolean = { true },
    ): List<EvidenceDraft>?

    /** Keeps [lines], what the AI service's own searches found for the song (*Up to 3 times*), none included. */
    suspend fun keep(
        song: SongWebQuery,
        lines: List<EvidenceDraft>,
    )

    /**
     * How a page the AI service's own search found (*Up to 3 times*) gives its line for [song],
     * retrieved at the time given, by the rules the app's own search keeps a result by: about the
     * recording, not a source the app reads itself, page boilerplate left out, cut as a web line is, and
     * marked with the track's language when written in it; null for no line. Asked once, so any free
     * lookup of the names it needs waits here rather than within a search's answer.
     */
    suspend fun pageLines(song: SongWebQuery): (WebPage, Instant) -> EvidenceDraft?

    /**
     * The song's web lines kept again, from now, when a card of the song's insight was written, "found
     * nothing" included: they last as long as the last card written from them (*Keep history for*), as
     * an artist's do. Nothing when none is kept.
     */
    suspend fun renew(song: SongWebQuery)
}

/**
 * A song to search the web for, or whose lines to keep (#958): the [track], as normalized, kept by
 * its lookup key; its [language] when the track's search in its language named one other than
 * English and *Search in the track's language* is not *Off*, in which the one search is made; and
 * the [resolution] Wikidata's names in that language are asked by, when there is one, this play's.
 */
class SongWebQuery(
    val track: NormalizedTrack,
    val language: Language? = null,
    val resolution: Resolution? = null,
)
