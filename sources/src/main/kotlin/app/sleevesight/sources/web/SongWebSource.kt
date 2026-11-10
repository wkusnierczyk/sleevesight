package app.sleevesight.sources.web

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.SongWebLookup
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.core.sources.WebPage
import kotlin.time.Instant

/**
 * The web lines about a song the other sources only list (#958; `design/architecture.md`, *Source
 * adapters*), for the song's insight under *Key insights*: the pages a web search for the song found,
 * kept per track ([PART]), by its lookup key, as an artist's are per artist ([ArtistWebSource]) and in
 * the same way: whatever *Keep what sources said about each artist* says, for as long as the song's
 * insight is kept, counted from the last card written from them ([renew]), "found nothing" included. A
 * search that fails keeps nothing, and is due again at the next write. Never throws but for
 * cancellation.
 */
class SongWebSource(
    private val web: SongWebSearch?,
    kept: ArtistResults = ArtistResults.None,
) : SongWebLookup {
    private val lines = KeptWebLines(PART, kept, BEFORE_DECODING)

    override suspend fun kept(song: SongWebQuery): List<EvidenceDraft>? = lines.kept(song.track.lookupKey)

    override suspend fun search(
        song: SongWebQuery,
        sent: () -> Unit,
        keeping: (List<EvidenceDraft>) -> Unit,
        mayPay: () -> Boolean,
    ): List<EvidenceDraft>? =
        web?.let { engine ->
            lines.search(song.track.lookupKey, keeping, mayPay) { engine.search(song, sent, mayPay) }
        }

    override suspend fun keep(
        song: SongWebQuery,
        lines: List<EvidenceDraft>,
    ) = this.lines.keep(song.track.lookupKey, lines)

    override suspend fun pageLines(song: SongWebQuery): (WebPage, Instant) -> EvidenceDraft? =
        web?.pageLines(song) ?: { _, _ -> null }

    override suspend fun renew(song: SongWebQuery) = lines.renew(song.track.lookupKey)

    companion object {
        /**
         * A song's web lines (#958), by the track's lookup key: what a web search for it found, the
         * app's or the AI service's, none included, kept whatever *Keep what sources said about each
         * artist* says (`PipelineWiring.TRACK_ONLY_PARTS`), and as long as the song's insight written from
         * them ([ArtistPart.keptAsHistory]: *Keep history for*), so its card's key stays the same across days.
         * Extraction 2 (#1165): Exa's text with its HTML character references decoded
         * (`ExaWebSearch.EXTRACTION_VERSION` 4), so a song's lines kept before are searched again.
         */
        val PART: ArtistPart =
            ArtistPart("song.web", extraction = 2, encoding = 1, sources = setOf(SourceId.WEB), keptAsHistory = true)

        /** [PART] before Exa's references were decoded (#1165), whose lines are carried over without one. */
        internal val BEFORE_DECODING: ArtistPart = PART.copy(extraction = 1)
    }
}
