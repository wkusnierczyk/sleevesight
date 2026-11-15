package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Edition
import app.sleevesight.core.resolution.MusicBrainzEvidence

/**
 * Whether a track's evidence holds anything beyond what Home already shows of it (#1142): the
 * title, the artists, the album and its year, and the release dates under *Dates*. Decided before
 * the key insight's call, from the whole evidence, so the same evidence decides it the same way when
 * the card is written and whenever it is served again ([InsightState.Written.nothingBeyondHome]).
 * The call is made either way; with nothing beyond, the card says under the insight that no more
 * source information was found, since the model could only retell the header.
 *
 * A line adds nothing beyond Home when it is one of these; any other line adds something (a
 * producer or player other than the artists, a writer other than the performer, an earlier release,
 * what a source says of the song, the artist, the album or its genres):
 * - the player's own release line ([SourceId.PLAYER]);
 * - MusicBrainz's first-release line naming the album the player reports
 *   ([MusicBrainzEvidence.firstRelease]): of the album type or of none, by the album's name; a single
 *   or an EP only where the player names its release as one (" - Single", " - EP"). Another release
 *   named, a single before the album among them, is an earlier release;
 * - MusicBrainz's line on the album itself, as `AlbumSource` writes it ("“Sundown” is an album by
 *   Gordon Lightfoot, first released in January 1974."), for the album the player reports;
 * - a credit line ([MusicBrainzEvidence.creditedNames], and Wikidata's producers of the album) that
 *   names only the artists Home shows ([namesOnly]);
 * - Wikidata's date line on the song or the album ("… was first published in 1974.");
 * - a count or list of what many listeners did ([EvidenceLine.aggregate]: Last.fm's listeners, tags
 *   and similar artists), which the prompt leaves out (`InsightPrompt.COUNTS_RULE`).
 *
 * Names and titles are compared folded ([ArtistFact.matchKey]), the player's album less its closing
 * bracketed edition parts ([Edition.strip]). Read on the lines' templates, never on free text: a
 * sentence of Wikipedia's that only restates the release is still a source's text, and counts.
 */
object HomeShown {
    /**
     * Whether no line of [evidence] adds to what Home shows of [nowPlaying], normalized as [track].
     * True for a set with no line, of which no insight is written (`EvidenceSet.nothingToNarrate`).
     */
    fun nothingBeyond(
        evidence: EvidenceSet,
        nowPlaying: NowPlaying,
        track: NormalizedTrack,
    ): Boolean {
        val artists =
            (listOf(nowPlaying.artists, track.primary) + track.artists + track.titleArtists)
                .map(ArtistFact::matchKey)
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedByDescending { it.length }
        val album = nowPlaying.album?.let { ArtistFact.matchKey(Edition.strip(it)) }?.takeIf { it.isNotEmpty() }
        return evidence.lines.all { shown(it, album, artists) }
    }

    // Whether [line] only says what Home shows, the [album] the player names and its [artists].
    private fun shown(
        line: EvidenceLine,
        album: String?,
        artists: List<String>,
    ): Boolean {
        val text = line.text
        val credited = credited(line)
        return when {
            line.source == SourceId.PLAYER || line.aggregate -> true
            credited != null -> namesOnly(credited, artists)
            line.source == SourceId.MUSICBRAINZ && line.scope == Scope.SONG ->
                MusicBrainzEvidence.firstRelease(text)?.let { (type, title) -> isAlbum(type, title, album) } == true
            line.source == SourceId.MUSICBRAINZ && line.scope == Scope.RELEASE ->
                ALBUM_LINE.find(text)?.let { album != null && ArtistFact.matchKey(it.groupValues[1]) == album } == true
            line.source == SourceId.WIKIDATA && line.scope != Scope.ARTIST -> WIKIDATA_DATE.matches(text)
            else -> false
        }
    }

    // The names a credit line gives: MusicBrainz's on the song, Wikidata's producers of the album.
    private fun credited(line: EvidenceLine): String? =
        when {
            line.source == SourceId.MUSICBRAINZ && line.scope == Scope.SONG ->
                MusicBrainzEvidence.creditedNames(line.text)
            line.source == SourceId.WIKIDATA && line.scope == Scope.RELEASE ->
                WIKIDATA_PRODUCERS.find(line.text)?.groupValues?.get(1)
            else -> null
        }

    // Whether a first release of [type] named [title] is the [album] the player names.
    private fun isAlbum(
        type: String,
        title: String,
        album: String?,
    ): Boolean {
        if (album == null) return false
        val named = ArtistFact.matchKey(title)
        return when (type) {
            "", "album" -> named == album
            "single" -> album == "$named - single"
            "ep" -> album == "$named - ep"
            else -> false
        }
    }

    /**
     * Whether [names], a credit's names as `EvidenceText.list` writes them ("A, B and C"), are only
     * [artists] (folded, longest first): with each artist's name taken out, nothing but the list's
     * commas and "and" is left. A name only partly an artist's ("Gordon Lightfoot Jr.") leaves the
     * rest, so it is another name.
     */
    internal fun namesOnly(
        names: String,
        artists: List<String>,
    ): Boolean {
        var left = " ${ArtistFact.matchKey(names)} "
        for (artist in artists) left = left.replace(" $artist ", "  ").replace(" $artist,", " ,")
        return left.split(' ', ',').none { it.isNotEmpty() && it != "and" && it != "&" }
    }

    // MusicBrainz's line on the album, as `AlbumSource.musicBrainzLine` writes it, its title in the group.
    private val ALBUM_LINE = Regex("^“(.*)” is an? [A-Za-z]+(?: by .*?)?(?:, first released .*)?\\.$")

    // Wikidata's producers of the album, as `AlbumSource.wikiLines` writes them, the names in the group.
    private val WIKIDATA_PRODUCERS = Regex("^“.*” was produced by (.+)\\.$")

    // Wikidata's date line on a song or an album, as `WikidataEvidence.dateLine` writes it.
    private val WIKIDATA_DATE = Regex("^“.*” was (?:first published|created) (?:in|on) .+\\.$")
}
