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
 *   or an EP only where the player names its release as one (" - Single", " - EP", "(EP)",
 *   "[Single]": [PlayerAlbum]), an album only where it does not. Another release
 *   named, a single before the album among them, is an earlier release;
 * - MusicBrainz's line on the album itself, as `AlbumSource` writes it ("“Sundown” is an album by
 *   Gordon Lightfoot, first released in January 1974."), for the album the player reports;
 * - a credit line ([MusicBrainzEvidence.creditedNames], and Wikidata's producers of the album) that
 *   names only the artists Home shows ([namesOnly]): the player's, and on a track whose composer is
 *   told apart, the authors its Composer row names (`ArtistSplit.authors`: the composers and who
 *   wrote the words, an aria's librettists among them). A performer's line with its instruments or
 *   role says more than the name, and adds something;
 * - Wikidata's date line on the song or the album ("… was first published in 1974.") when *Dates*
 *   shows that date: the date of a MusicBrainz first-release line, or of its line on the album, in
 *   the same evidence, the same words, or the same year for a date given as a year only. Another
 *   date, a work's publication long before its recording say, adds something;
 * - a count or list of what many listeners did ([EvidenceLine.aggregate]: Last.fm's listeners, tags
 *   and similar artists), which the prompt leaves out (`InsightPrompt.COUNTS_RULE`).
 *
 * Names and titles are compared folded ([ArtistFact.matchKey]), the player's album less its closing
 * bracketed edition parts ([Edition.strip]). Read on the lines' templates, never on free text: a
 * sentence of Wikipedia's that only restates the release is still a source's text, and counts.
 */
object HomeShown {
    /**
     * Whether no line of [evidence] adds to what Home shows of [nowPlaying], normalized as [track],
     * with the [authors] its Composer row names (`ArtistSplit.authors`; none without that row). True
     * for a set with no line, of which no insight is written (`EvidenceSet.nothingToNarrate`).
     */
    fun nothingBeyond(
        evidence: EvidenceSet,
        nowPlaying: NowPlaying,
        track: NormalizedTrack,
        authors: List<String> = emptyList(),
    ): Boolean {
        val artists =
            (listOf(nowPlaying.artists, track.primary) + track.artists + track.titleArtists + authors)
                .map(ArtistFact::matchKey)
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedByDescending { it.length }
        val shown = Shown(nowPlaying.album?.let(PlayerAlbum::of), artists, datesShown(evidence))
        return evidence.lines.all { shown.only(it) }
    }

    /**
     * The release the player names, as [of] reads it: its [name] folded ([ArtistFact.matchKey]), less
     * the closing edition parts ([Edition.strip]) and the mark of a single or an EP, and that mark
     * ([kind]: `single` or `ep`, null for none). The player marks it " - Single", " - EP", or in
     * brackets, "(EP)", "[Single]", which [Edition.strip] takes as an edition and drops.
     */
    internal class PlayerAlbum(
        val name: String,
        val kind: String?,
    ) {
        companion object {
            /** [release] as the player names it; null when nothing is left of its name. */
            fun of(release: String): PlayerAlbum? {
                val marked = KIND.find(release.trim())
                val rest = marked?.groupValues?.get(1) ?: release
                val kind = marked?.groupValues?.get(2)?.lowercase()
                val name = ArtistFact.matchKey(Edition.strip(rest))
                return if (name.isEmpty()) null else PlayerAlbum(name, kind)
            }

            // A closing " - Single" or " - EP", or "(EP)" or "[Single]", any case; the mark in the second group.
            private val KIND =
                Regex("^(.*?)(?:\\s+-\\s+|\\s*[(\\[])(single|ep)[)\\]]?$", RegexOption.IGNORE_CASE)
        }
    }

    // What Home shows: the [album] the player names, the [artists] and authors, and the [dates] *Dates* gives.
    private class Shown(
        val album: PlayerAlbum?,
        val artists: List<String>,
        val dates: Set<String>,
    ) {
        // Whether [line] only says what Home shows.
        fun only(line: EvidenceLine): Boolean {
            val credited = credited(line)
            return when {
                line.source == SourceId.PLAYER || line.aggregate -> true
                credited != null -> namesOnly(credited, artists)
                line.source == SourceId.MUSICBRAINZ -> release(line)
                line.source == SourceId.WIKIDATA && line.scope != Scope.ARTIST ->
                    WIKIDATA_DATE.find(line.text)?.let { dateShown(it.groupValues[1], dates) } == true
                else -> false
            }
        }

        // Whether MusicBrainz's [line] names only the album the player names: its first release, or the album itself.
        private fun release(line: EvidenceLine): Boolean =
            when (line.scope) {
                Scope.SONG ->
                    MusicBrainzEvidence.firstRelease(line.text)?.let { (type, title) -> isAlbum(type, title, album) }
                Scope.RELEASE ->
                    ALBUM_LINE.find(line.text)?.let { ArtistFact.matchKey(it.groupValues[1]) == album?.name }
                else -> null
            } == true
    }

    // The dates *Dates* shows, as the lines word them: each MusicBrainz first-release line's, and its
    // line on the album's ("in 1974", "in January 1974", "on 25 March 1974").
    private fun datesShown(evidence: EvidenceSet): Set<String> =
        evidence.lines
            .filter { it.source == SourceId.MUSICBRAINZ }
            .mapNotNull { line ->
                when (line.scope) {
                    Scope.SONG -> FIRST_RELEASE_DATE.find(line.text)
                    Scope.RELEASE -> ALBUM_LINE.find(line.text)?.let { ALBUM_DATE.find(line.text) }
                    else -> null
                }?.groupValues?.get(1)
            }.toSet()

    // Whether [date], as a line words it, is one of the [dates] shown: the same words, or, for a date
    // given as a year only ("in 1974"), the same year as one of them.
    private fun dateShown(
        date: String,
        dates: Set<String>,
    ): Boolean {
        val year = YEAR_ONLY.find(date)?.groupValues?.get(1)
        return date in dates || (year != null && dates.any { YEAR.find(it)?.value == year })
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

    // Whether a first release of [type] named [title] is the [album] the player names: by its name, of
    // the kind the player marks it, or an album where it marks none; a release of no type by name alone.
    private fun isAlbum(
        type: String,
        title: String,
        album: PlayerAlbum?,
    ): Boolean {
        if (album == null || ArtistFact.matchKey(title) != album.name) return false
        return when (type) {
            "" -> true
            "album" -> album.kind == null
            "single", "ep" -> album.kind == type
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

    // MusicBrainz's line on the album, as `AlbumSource.musicBrainzLine` writes it, its title in the
    // group; its type is `AlbumSource.typeOf`'s, the secondary types first ("live album",
    // "compilation album", "dj-mix album", "mixtape/street album"), so the slot is read whole.
    private val ALBUM_LINE = Regex("^“(.*)” is an? [^,]+?(?: by .*?)?(?:, first released .*)?\\.$")

    // The date of MusicBrainz's line on the album, and of its first-release line, as `EvidenceText.date` words it.
    private val ALBUM_DATE = Regex(", first released ((?:in|on) [^,]+)\\.$")
    private val FIRST_RELEASE_DATE = Regex(" was first released ((?:in|on) [^,]+), on ")

    // A date worded as a year only, and a year.
    private val YEAR_ONLY = Regex("^in (\\d{4})$")
    private val YEAR = Regex("\\d{4}")

    // Wikidata's producers of the album, as `AlbumSource.wikiLines` writes them, the names in the group.
    private val WIKIDATA_PRODUCERS = Regex("^“.*” was produced by (.+)\\.$")

    // Wikidata's date line on a song or an album, as `WikidataEvidence.dateLine` writes it, its date in the group.
    private val WIKIDATA_DATE = Regex("^“.*” was (?:first published|created) ((?:in|on) .+)\\.$")
}
