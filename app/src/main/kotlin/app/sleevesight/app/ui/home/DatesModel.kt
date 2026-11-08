package app.sleevesight.app.ui.home

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.ReleaseType
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.text.DateWhen

// The dates the home screen shows (#556, #724): the year after the album, and the *Dates* panel's
// sentences, or why it has none, worked out from what the pipeline publishes, as plain functions.

/**
 * The header's years (#556): [album], the year of the release being played, shown after the album.
 * When the recording first came out, and the rest of the dates, are said in the *Dates* panel under
 * it ([homeDates], #724).
 */
internal data class HeaderYears(
    val album: String? = null,
)

/**
 * The years [header]'s album shows ([HeaderYears]): chosen from the releases of the track's
 * recordings that the player's album names and the player's own year ([NowPlaying.year]), by
 * [ReleaseDate.albumYear]'s rules (design, *Key data*), the album's name read by
 * [NameMatcher.albumKey] ("Vol. 2" is "Volume 2", #724), and, when its name gives several years
 * and the player's none of them, the track's first release's when it is one of them (#1058); with
 * no album reported, none. From MusicBrainz's result, which keeps the releases in the retrieval
 * cache, so a track served from the cache (which makes no resolution) shows the same year; else
 * from the run's resolution.
 */
internal fun headerYears(
    track: TrackState?,
    header: NowPlaying,
): HeaderYears = HeaderYears(album = AlbumDates.of(track, header).year)

/** A release's primary type, when *Dates* names it (#724): "the single …", "the EP …". */
internal enum class ReleaseKind {
    SINGLE,
    EP,
    ;

    companion object {
        /** MusicBrainz's primary type as named, or null for any other (an album, a compilation). */
        fun of(type: String?): ReleaseKind? =
            when (type) {
                "Single" -> SINGLE
                "EP" -> EP
                else -> null
            }
    }
}

/**
 * A sentence of the *Dates* panel under the album (#724), one per date known, each [date] as
 * MusicBrainz gives it (`2014`, `2014-07`, `2014-07-29`), said as precisely ([DateWhen]).
 */
internal sealed interface DateSentence {
    /**
     * "This album was originally released {when}.": the release of the album's name whose year the
     * album line shows; "This single …" or "This EP …" when MusicBrainz's release group of it is
     * one, as [kind] names it (#1058).
     */
    data class Album(
        val date: String,
        val kind: ReleaseKind? = null,
    ) : DateSentence

    /**
     * "This track was first released on this album.": its first release is the album being played,
     * whose date the [Album] sentence above says (#1058); "… on this single." or "… on this EP." as
     * [kind] names it, as [Album]'s does.
     */
    data class FirstHere(
        val kind: ReleaseKind? = null,
    ) : DateSentence

    /**
     * "This track was first released on {album} {when}.", on the single or EP [kind] names; with
     * no [title] known, "This track was first released {when}.".
     */
    data class First(
        val date: String,
        val title: String?,
        val kind: ReleaseKind?,
    ) : DateSentence

    /**
     * "The song was originally recorded by {artist} {when}, and released on {album}.", on the
     * single or EP [kind] names; with no [title] known, "The song was originally recorded by {artist}
     * {when}.": a cover's original, recorded on [date].
     */
    data class Original(
        val date: String,
        val artist: String,
        val title: String?,
        val kind: ReleaseKind?,
    ) : DateSentence
}

/**
 * The sentences of the *Dates* panel for [track] beside [header] (#724), in this order, each when
 * its date is known: the album's release, of the album's name whose year the album line shows (the
 * earliest date of the named releases in that year, a single or EP of its title left out beside an
 * album; none when the year is the player's own and no named release has it); the track's first
 * release, "on this album" when it is the album being played ([AlbumDates.has]: its release group
 * is one of those whose year the album line shows, and its year that year, or, when either side
 * has no release group, its title has the album's key) and the album's sentence says its date (else
 * the date would be lost, #1058: then said with its own date and title), "this single" or "this EP"
 * when the album is one; and, for a cover, the song's original recording, with the release it
 * first came out on when known. Empty when no date is known: the panel then says why ([NoDates]).
 *
 * From MusicBrainz's result, which keeps them in the retrieval cache, else the run's resolution,
 * as the album's year is ([headerYears]). A result kept before #724 has the first release's date
 * only, and no original: the track's sentence says the date alone, and there is no cover sentence.
 */
internal fun homeDates(
    track: TrackState?,
    header: NowPlaying,
): List<DateSentence> {
    val kept = keptMusicBrainz(track)
    val resolution = track?.resolution
    val album = AlbumDates.of(track, header)
    val sentences = mutableListOf<DateSentence>()
    album.date?.let { sentences += DateSentence.Album(it, album.kind) }
    val first = firstReleaseOf(track)
    val firstDate = first?.date ?: kept?.firstReleased ?: resolution?.firstReleaseDate
    val here = first != null && album.date != null && album.has(first)
    when {
        here -> sentences += DateSentence.FirstHere(album.kind)
        firstDate != null && DateWhen.of(firstDate) != null ->
            sentences += DateSentence.First(firstDate, first?.title, ReleaseKind.of(first?.type))
    }
    // A fresh lookup's original wins over a kept one without its release (#724): a *Look again*
    // that finds the release a failed lookup missed keeps the earlier state, which lacks it.
    val original = kept?.original?.takeIf { it.release != null } ?: resolution?.original ?: kept?.original
    if (original != null && DateWhen.of(original.date) != null) {
        val release = original.release
        sentences +=
            DateSentence.Original(original.date, original.artistCredit, release?.title, ReleaseKind.of(release?.type))
    }
    return sentences
}

/**
 * Why the *Dates* panel has no sentence ([homeDates] empty), which it says in their place (#724):
 * the button shows for every track.
 */
internal enum class NoDates {
    /** MusicBrainz is still being asked, or the pipeline has not reached the track yet: "Looking on MusicBrainz…". */
    LOOKING,

    /** MusicBrainz is switched off in Settings: "MusicBrainz is disabled.", and a link to *Settings → Sources*. */
    OFF,

    /** MusicBrainz could not be reached (or its adapter failed): "MusicBrainz could not be reached.", with *Retry*. */
    UNREACHABLE,

    /** MusicBrainz accepted no recording (none found, or a near miss rejected): "MusicBrainz didn’t find this song." */
    NOT_FOUND,

    /** MusicBrainz found the song, and gives no date of it: "MusicBrainz gives no dates for this song." */
    NO_DATES,
    ;

    companion object {
        /**
         * Why [track] has no dates, from MusicBrainz's state in it: its result as the run gave it or
         * the retrieval cache kept it.
         */
        fun of(track: TrackState?): NoDates =
            when (val state = track?.sources?.get(SourceId.MUSICBRAINZ)) {
                null, SourceState.Pending -> LOOKING
                is SourceState.Failed -> UNREACHABLE
                is SourceState.Done ->
                    when (state.result.outcome) {
                        Outcome.SourceOff -> OFF
                        Outcome.Unreachable -> UNREACHABLE
                        Outcome.None, is Outcome.Rejected -> NOT_FOUND
                        else -> NO_DATES
                    }
            }
    }
}

// The album the player names and what the album line shows of it: its year, and of the releases its
// name names in that year (without a same-year single or EP of its title beside an album, #724), the
// earliest date, their release groups, and their type when all are singles or EPs (#1058).
private class AlbumDates(
    val name: String?,
    val year: String?,
    val date: String?,
    val groups: Set<String>,
    val kind: ReleaseKind?,
) {
    /**
     * Whether [first] is this album: a known release group decides (a single and a later album of
     * one title are not one release), with the year: a reissue in the original's group, out in
     * another year than [first] (*Thriller 25*, 2008, in *Thriller*'s, 1982), is not where the track
     * first came out (#1065), unless the first release's year is unknown. Only without a group on
     * either side does the title, as [NameMatcher.albumKey] reads it.
     */
    fun has(first: FirstRelease): Boolean =
        name != null &&
            if (first.releaseGroupMbid != null && groups.isNotEmpty()) {
                first.group in groups && sameYear(first.date)
            } else {
                ALBUMS.albumKey(first.title) == ALBUMS.albumKey(name)
            }

    // Whether [date] is in the year the album line shows, or its year is unknown. Only asked with
    // [groups], which are known only with a year shown.
    private fun sameYear(date: String): Boolean = ReleaseDate.yearIn(date).let { it == null || it == year }

    companion object {
        fun of(
            track: TrackState?,
            header: NowPlaying,
        ): AlbumDates {
            val kept = keptMusicBrainz(track)
            val dates = kept?.releaseDates?.ifEmpty { null } ?: track?.resolution?.releaseDates.orEmpty()
            val name = header.album?.takeIf(String::isNotBlank)
            val named = name?.let { ReleaseDate.named(it, dates, ALBUMS::albumKey) }.orEmpty()
            val year =
                name?.let {
                    ReleaseDate.albumYear(it, dates, header.year?.toString(), ALBUMS::albumKey)
                        ?: settledBy(firstReleaseOf(track), named)
                }
            val album = if (year == null) emptyList() else albumsOf(named.filter { it.releaseYear == year }, named)
            val date = earliest(album.mapNotNull { it.date })
            return AlbumDates(name, year, date, album.map { it.group }.toSet(), kindNamed(album, date))
        }

        // The type of [album]'s releases as its sentence names it (#1058): when every one is a
        // single or an EP, the kind of the one whose [date] the sentence gives (a single and an EP
        // of the name in one year, no album: the earlier one); null when any is an album or of no
        // type known.
        private fun kindNamed(
            album: List<ReleaseDate>,
            date: String?,
        ): ReleaseKind? {
            val short = album.isNotEmpty() && album.all { ReleaseKind.of(it.type) != null }
            val dated = album.filter { it.date != null }
            val said = dated.firstOrNull { it.date == date } ?: dated.minByOrNull { it.date.orEmpty() }
            return said?.takeIf { short }?.let { ReleaseKind.of(it.type) }
        }

        // The year [first] gives an album whose name has releases in several years, none of them the
        // player's (#1058: *Thriller*, 1982 and its reissues): its own, when it is a release of that
        // name ([named], by its title's key) out in one of their years. Not a single's or an EP's
        // beside an album of the name: the title track's single may be out in a year no album of
        // the name is, and the album played is then not it. Null otherwise.
        private fun settledBy(
            first: FirstRelease?,
            named: List<ReleaseDate>,
        ): String? {
            val year = first?.date?.let(ReleaseDate::yearIn) ?: return null
            val key = ALBUMS.albumKey(first.title)
            val short = ReleaseKind.of(first.type) != null
            return year.takeIf {
                named.any { it.releaseYear == year && ALBUMS.albumKey(it.title) == key } &&
                    (!short || named.all { ReleaseKind.of(it.type) != null })
            }
        }

        // Of [inYear], the album's releases: a single or EP of its title out the same year (the
        // title track's single) is not the album, so it is left out when a release of another type,
        // or of none known, is named too (#724). When only singles or EPs are dated in the year and
        // [named] has an undated album of the name, that album is the one: no date, its groups. A
        // release kept before its type was has none, so none is left out of an entry kept then. A
        // track that is the title track's single itself, with the album out the same year, is
        // therefore never "on this album": the panel then says less, never something wrong.
        private fun albumsOf(
            inYear: List<ReleaseDate>,
            named: List<ReleaseDate>,
        ): List<ReleaseDate> {
            val (short, other) = inYear.partition { ReleaseKind.of(it.type) != null }
            val undated = named.filter { it.date == null && it.type != null && ReleaseKind.of(it.type) == null }
            return when {
                short.isNotEmpty() && other.isNotEmpty() -> other
                short.isNotEmpty() && undated.isNotEmpty() -> undated
                else -> inYear
            }
        }

        // The earliest of [dates], as precise as the dates that refine it allow: of `2014` and
        // `2014-07-29`, the day.
        private fun earliest(dates: List<String>): String? =
            generateSequence(dates.minOrNull()) { at ->
                dates.filter { it.length > at.length && it.startsWith(at) }.minOrNull()
            }.lastOrNull()
    }
}

/**
 * The sentences of what *Search with AI* found for *Dates* ([answer], #773), as [homeDates] says
 * MusicBrainz's, each part only where the answer gives it: the album's release; the track's first
 * release, "on this album" when its title is the album's beside [header] ([NameMatcher.albumKey]), it
 * is not named a single or an EP, and the answer gives the album's date too (else its own date would
 * be lost: then said with it), in the first release's year when that is known (a reissue's later date
 * is not where the track first came out, #1065); and a cover's original.
 */
internal fun aiDates(
    answer: SearchAnswer.Dates,
    header: NowPlaying,
): List<DateSentence> = aiDatesCited(answer, header).map { it.first }

/**
 * [aiDates]' sentences, each with the page it came from (#801), null for an answer from the model's
 * knowledge.
 */
internal fun aiDatesCited(
    answer: SearchAnswer.Dates,
    header: NowPlaying,
): List<Pair<DateSentence, String?>> {
    val sentences = mutableListOf<Pair<DateSentence, String?>>()
    answer.album?.let { sentences += DateSentence.Album(it) to answer.albumSource }
    answer.first?.let { first ->
        val album = header.album?.takeIf(String::isNotBlank)
        val title = first.title
        val here =
            answer.album != null &&
                album != null &&
                title != null &&
                kindOf(first.type) == null &&
                ALBUMS.albumKey(title) == ALBUMS.albumKey(album) &&
                ReleaseDate.yearIn(first.date).let { it == null || it == answer.album?.let(ReleaseDate::yearIn) }
        val sentence = if (here) DateSentence.FirstHere() else DateSentence.First(first.date, title, kindOf(first.type))
        sentences += sentence to first.source
    }
    answer.original?.let { original ->
        val release = original.release
        sentences +=
            DateSentence.Original(original.date, original.artist, release?.title, kindOf(release?.type)) to
            original.source
    }
    return sentences
}

// A release's type as the answer named it, as *Dates* names it: an album, none.
private fun kindOf(type: ReleaseType?): ReleaseKind? =
    when (type) {
        ReleaseType.SINGLE -> ReleaseKind.SINGLE
        ReleaseType.EP -> ReleaseKind.EP
        ReleaseType.ALBUM, null -> null
    }

/**
 * The recording's first release as MusicBrainz gives it for [track], as *Dates* reads it: from its
 * result, else the run's resolution; for what *Search with AI* tells the model (#773).
 */
internal fun firstReleaseOf(track: TrackState?): FirstRelease? =
    keptMusicBrainz(track)?.firstRelease ?: track?.resolution?.firstRelease

// MusicBrainz's result for [track], as the run gave it or the retrieval cache kept it.
private fun keptMusicBrainz(track: TrackState?): SourceResult? =
    (track?.sources?.get(SourceId.MUSICBRAINZ) as? SourceState.Done)?.result

// A release's year, null when undated or unreadable ([ReleaseDate.yearIn]).
private val ReleaseDate.releaseYear: String? get() = date?.let(ReleaseDate::yearIn)

// A first release's group as [ReleaseDate.group] has it: the release group's id, else the release's own.
private val FirstRelease.group: String get() = releaseGroupMbid ?: releaseMbid

// Album names compared as sources write them differently, "Vol." as "Volume" ([NameMatcher.albumKey]).
private val ALBUMS = NameMatcher(JvmAccentFolder)
