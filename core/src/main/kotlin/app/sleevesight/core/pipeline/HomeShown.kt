package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Edition
import app.sleevesight.core.resolution.MusicBrainzEvidence
import app.sleevesight.core.sources.ComposedDate

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
 *   Gordon Lightfoot, first released in January 1974."), for the album the player reports, of the
 *   kind it marks it as ([isAlbum]: an album where it marks none, a single or an EP where it marks
 *   one), by only the artists shown ([namesOnly]). A secondary type ("a live album", "a soundtrack
 *   album") or another credit ("by Various Artists") is more than Home shows, and adds something;
 * - a credit line ([MusicBrainzEvidence.creditedNames], and Wikidata's producers of the album) that
 *   names only the artists Home shows ([namesOnly]): the player's, and on a track whose composer is
 *   told apart, the authors its Composer row names (`ArtistSplit.authors`: the composers and who
 *   wrote the words, an aria's librettists among them). A performer's line with its instruments or
 *   role says more than the name, and adds something;
 * - Wikidata's date line on the song or the album ("… was first published in 1974.") when *Dates*
 *   shows that date: the date of a MusicBrainz first-release line, or of its line on the album, in
 *   the same evidence, the same words, or the same year for a date given as a year only; or, for a
 *   date given as a year only, a year of when the music was composed as *Dates* says it
 *   ([ComposedDate]: its first or last year, said only when before the earliest release year
 *   shown). Another date adds something;
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
     * with the [authors] its Composer row names (`ArtistSplit.authors`; none without that row) and
     * when its music was [composed], as *Dates* has it (null for none). True for a set with no line,
     * of which no insight is written (`EvidenceSet.nothingToNarrate`).
     */
    fun nothingBeyond(
        evidence: EvidenceSet,
        nowPlaying: NowPlaying,
        track: NormalizedTrack,
        authors: List<String> = emptyList(),
        composed: ComposedDate? = null,
    ): Boolean {
        val shown = shown(evidence, nowPlaying, track, authors, composed)
        return evidence.lines.all { it.aggregate || shown.restates(it) }
    }

    /**
     * Which lines of [evidence] only restate what Home shows of [nowPlaying] (as [nothingBeyond] reads
     * them, less the counts, which say nothing Home shows): a prompt with other lines to tell leaves
     * them out (`PromptEvidence.of`), so the model tells the story beyond the header rather than
     * retelling it beside that story.
     */
    fun restatement(
        evidence: EvidenceSet,
        nowPlaying: NowPlaying,
        track: NormalizedTrack,
        authors: List<String> = emptyList(),
        composed: ComposedDate? = null,
    ): (EvidenceLine) -> Boolean = shown(evidence, nowPlaying, track, authors, composed)::restates

    // What Home shows of [nowPlaying], as [nothingBeyond] takes it.
    private fun shown(
        evidence: EvidenceSet,
        nowPlaying: NowPlaying,
        track: NormalizedTrack,
        authors: List<String>,
        composed: ComposedDate?,
    ): Shown {
        val artists =
            (listOf(nowPlaying.artists, track.primary) + track.artists + track.titleArtists + authors)
                .map(ArtistFact::matchKey)
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedByDescending { it.length }
        val dates = datesShown(evidence)
        return Shown(nowPlaying.album?.let(PlayerAlbum::of), artists, dates, composedShown(composed, dates))
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

    // What Home shows: the [album] the player names, the [artists] and authors, the release [dates]
    // *Dates* gives, and the [composed] years it says.
    private class Shown(
        val album: PlayerAlbum?,
        val artists: List<String>,
        val dates: Set<String>,
        val composed: Set<String>,
    ) {
        // Whether [line] only says what Home shows; a count never does.
        fun restates(line: EvidenceLine): Boolean {
            val credited = credited(line)
            return when {
                line.aggregate -> false
                line.source == SourceId.PLAYER -> true
                credited != null -> namesOnly(credited, artists)
                line.source == SourceId.MUSICBRAINZ -> release(line)
                line.source == SourceId.WIKIDATA && line.scope != Scope.ARTIST ->
                    WIKIDATA_DATE.find(line.text)?.let { dateShown(it.groupValues[1], dates, composed) } == true
                else -> false
            }
        }

        // Whether MusicBrainz's [line] names only the album the player names: its first release, or the album itself.
        private fun release(line: EvidenceLine): Boolean =
            when (line.scope) {
                Scope.SONG ->
                    MusicBrainzEvidence.firstRelease(line.text)?.let { (type, title) -> isAlbum(type, title, album) }
                Scope.RELEASE -> ALBUM_LINE.find(line.text)?.let(::albumOnly)
                else -> null
            } == true

        // Whether MusicBrainz's line on the album, [found] by [ALBUM_LINE], names only the album the
        // player names, of its kind, and the artists shown: its type and credit are not on Home.
        private fun albumOnly(found: MatchResult): Boolean {
            val (title, type, credit) = found.destructured
            val kind = type.lowercase().takeUnless { it == NO_TYPE }.orEmpty()
            return isAlbum(kind, title, album) && (credit.isEmpty() || namesOnly(credit, artists))
        }
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
    // given as a year only ("in 1974"), the same year as one of them, or one of the [composed] years.
    private fun dateShown(
        date: String,
        dates: Set<String>,
        composed: Set<String>,
    ): Boolean {
        val year = YEAR_ONLY.find(date)?.groupValues?.get(1)
        return date in dates || (year != null && (year in composed || dates.any { YEAR.find(it)?.value == year }))
    }

    // The years *Dates* says the music was composed in, as `composedSentence` decides: its first and
    // last, when the last is before the earliest release year shown; none beside no release date.
    private fun composedShown(
        composed: ComposedDate?,
        dates: Set<String>,
    ): Set<String> {
        val earliest = dates.mapNotNull { YEAR.find(it)?.value }.minOrNull()
        return if (composed != null && earliest != null && composed.to < earliest) {
            setOf(composed.from, composed.to)
        } else {
            emptySet()
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
     * rest, so it is another name. Full stops are taken out of both, so a name ending in one ("Gary
     * Clark Jr.") reads the same first in the list as last, where the folding drops it.
     */
    internal fun namesOnly(
        names: String,
        artists: List<String>,
    ): Boolean {
        var left = " ${ArtistFact.matchKey(names).replace(".", "")} "
        for (artist in artists.map { it.replace(".", "") }.sortedByDescending { it.length }) {
            left = left.replace(" $artist ", "  ").replace(" $artist,", " ,")
        }
        return left.split(' ', ',').none { it.isNotEmpty() && it != "and" && it != "&" }
    }

    // MusicBrainz's line on the album, as `AlbumSource.musicBrainzLine` writes it: its title, its type
    // and its credit in the groups (the credit empty for none). The type is `AlbumSource.typeOf`'s,
    // the secondary types first ("live album", "compilation album", "mixtape/street album"), so the
    // slot is read whole; "release" for a release group of no primary type ([NO_TYPE]).
    private val ALBUM_LINE = Regex("^“(.*)” is an? ([^,]+?)(?: by (.*?))?(?:, first released .*)?\\.$")

    // `AlbumSource.typeOf`'s type for a release group of no primary type: as a first release of none.
    private const val NO_TYPE = "release"

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

/**
 * When [this] track's music was composed, as *Dates* reads it (#1132, the app's `composedSentence`):
 * MusicBrainz's result's, else the run's resolution's, else Wikipedia's result's; null for none.
 */
internal fun TrackState.composed(): ComposedDate? =
    (sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result?.composed
        ?: resolution?.composed
        ?: (sources[SourceId.WIKIPEDIA] as? SourceState.Done)?.result?.composed
