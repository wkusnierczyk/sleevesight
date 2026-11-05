package app.sleevesight.sources.web

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.sources.WebPage
import app.sleevesight.sources.KeptDrafts
import app.sleevesight.sources.OneSearchAtATime
import app.sleevesight.sources.lastfm.Lru

/**
 * The web lines about an artist the other sources say nothing about (#912; `design/architecture.md`,
 * *Source adapters*), for the artist's insight under *Key insights*: the pages a web search for the
 * artist found, kept per artist, as an album's are (`AlbumSource`, #885).
 *
 * - [search], *AI web search* set to *Once*: the app's own search for the artist ([web],
 *   `ExaArtistSearch`), one at a time per artist (a second write of the artist while one searches
 *   takes its outcome), telling its caller when a request is sent, and only then (not for lines
 *   kept, or an artist whose name cannot be checked), so a search is counted as paid only when it is.
 * - [keep], *Up to 3 times*: the pages the AI service's own searches found while it wrote the
 *   artist's insight (`ItemInsights.writeSearching`), as given.
 *
 * Either way, what was found, none included ("found nothing"), is kept ([PART]) by the artist's
 * MusicBrainz id, else its name folded, in [kept], which the app keeps whatever *Keep what sources
 * said about each artist* says (`PipelineWiring.TRACK_ONLY_PARTS`), as it keeps a track's own web
 * findings, and for as long as the artist's insight is kept (*Keep history for*,
 * [ArtistPart.keptAsHistory]), not for the web's own lifetime, counted from the last card written
 * from them ([renew]): a later write, or a later gathering, of the artist finds the same lines with
 * no search, and so the insight under the same key, on any day it is kept. A search that fails keeps
 * nothing, and is due again at the next write. With a [kept] that keeps nothing
 * ([ArtistResults.persists] false: the CLI, tests), in memory for the most recent [REMEMBERED]
 * artists instead. Never throws but for cancellation.
 */
class ArtistWebSource(
    private val web: ArtistWebSearch?,
    private val names: NameMatcher,
    private val kept: ArtistResults = ArtistResults.None,
) : ArtistWebLookup {
    // The web's lines by artist, while [kept] keeps nothing.
    private val remembered = Lru<String, List<EvidenceDraft>>(REMEMBERED)

    // The searches under way, by artist ([keyOf]): one at a time per artist.
    private val searching = OneSearchAtATime<List<EvidenceDraft>>()

    override suspend fun kept(artist: ArtistWebQuery): List<EvidenceDraft>? {
        val key = keyOf(artist)
        return if (kept.persists) kept.get(PART, key)?.let { KeptDrafts.decode(it.value) } else remembered.get(key)
    }

    override suspend fun search(
        artist: ArtistWebQuery,
        sent: () -> Unit,
        mayPay: () -> Boolean,
    ): List<EvidenceDraft>? =
        web?.let { engine ->
            try {
                searching.once(keyOf(artist)) { kept(artist) ?: searched(engine, artist, sent, mayPay) }
            } catch (
                @Suppress("SwallowedException") e: NotPaid,
            ) {
                // Its gate closed after the waits: nothing sent, nothing kept; a write waiting for this
                // one searches, or reads, itself.
                null
            }
        }

    override suspend fun keep(
        artist: ArtistWebQuery,
        lines: List<EvidenceDraft>,
    ) {
        val key = keyOf(artist)
        if (kept.persists) kept.keep(PART, key, KeptDrafts.encode(lines)) else remembered.put(key, lines)
    }

    override fun pageLine(
        artist: ArtistWebQuery,
        page: WebPage,
    ): String? = web?.line(artist.name, page) ?: page.text.takeIf { web == null }

    override suspend fun renew(artist: ArtistWebQuery) {
        val key = keyOf(artist)
        if (kept.persists) {
            kept.get(PART, key)?.let { kept.keep(PART, key, it.value) }
        } else {
            remembered.get(key)?.let { remembered.put(key, it) }
        }
    }

    // What [engine] finds for [artist], none included, kept; null when it failed, and nothing is kept.
    // [mayPay] is asked right before the request, with no suspension between; closed, [NotPaid].
    private suspend fun searched(
        engine: ArtistWebSearch,
        artist: ArtistWebQuery,
        sent: () -> Unit,
        mayPay: () -> Boolean,
    ): List<EvidenceDraft>? {
        if (!mayPay()) throw NotPaid()
        val found =
            when (val answer = engine.search(artist.name, sent)) {
                is Lookup.Found -> answer.value
                Lookup.NotFound -> emptyList()
                is Lookup.Failed -> null
            }
        found?.let { keep(artist, it) }
        return found
    }

    // The artist's key: its MusicBrainz id, else its name folded, as its card's entity is keyed.
    private fun keyOf(artist: ArtistWebQuery): String =
        artist.mbid?.let { "mb:$it" } ?: "name:${names.key(artist.name)}"

    // A search its caller's gate refused right before the request (#912's review).
    private class NotPaid : Exception("not paid")

    companion object {
        /**
         * An artist's web lines (#912), by its MusicBrainz id, else its name folded: what a web search
         * for it found, the app's or the AI service's, none included, kept whatever *Keep what sources
         * said about each artist* says (`PipelineWiring.TRACK_ONLY_PARTS`), and as long as the
         * artist's insight written from them ([ArtistPart.keptAsHistory]: *Keep history for*), so its
         * card's key stays the same across days, as an album's web lines are (`AlbumSource.WEB_PART`).
         */
        val PART: ArtistPart =
            ArtistPart("artist.web", extraction = 1, encoding = 1, sources = setOf(SourceId.WEB), keptAsHistory = true)

        /** The artists whose web lines are kept in memory, without a [kept] that keeps them. */
        const val REMEMBERED: Int = 256
    }
}
