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
 * [NameMatcher.albumKey] ("Vol. 2" is "Volume 2", #724); with no album reported, none. From
 * MusicBrainz's result, which keeps the releases in the retrieval cache, so a track served from the
 * cache (which makes no resolution) shows the same year; else from the run's resolution.
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
    /** "This album was released {when}.": the release of the album's name whose year the album line shows. */
    data class Album(
        val date: String,
    ) : DateSentence

    /** "This track was first released on this album.": its first release is the album being played. */
    data object FirstHere : DateSentence

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
     * "The song was originally recorded {when} by {artist}, and released on {album}.", on the
     * single or EP [kind] names; with no [title] known, "The song was originally recorded {when} by
     * {artist}.": a cover's original, recorded on [date].
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
 * earliest date of the named releases in that year; none when the year is the player's own and no
 * named release has it); the track's first release, "on this album" when it is the album being
 * played ([AlbumDates.has]: its release group is one of those whose year the album line shows, or,
 * when either side has no release group, its title has the album's key); and, for a cover, the
 * song's original recording, with the release it first came out on when known. Empty when no date
 * is known: the panel then says why ([NoDates]).
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
    album.date?.let { sentences += DateSentence.Album(it) }
    val first = kept?.firstRelease ?: resolution?.firstRelease
    val firstDate = first?.date ?: kept?.firstReleased ?: resolution?.firstReleaseDate
    val here = first != null && album.has(first)
    when {
        here -> sentences += DateSentence.FirstHere
        firstDate != null && DateWhen.of(firstDate) != null ->
            sentences += DateSentence.First(firstDate, first?.title, ReleaseKind.of(first?.type))
    }
    val original = kept?.original ?: resolution?.original
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

/**
 * A date as precisely as MusicBrainz gives it (#724): a [Day], a [Month] or a [Year], said "on 29
 * July 2014", "in July 2014", "in 2014". A part that is not a day or a month leaves the date less
 * precise; no year, no date.
 */
internal sealed interface DateWhen {
    val year: String

    data class Day(
        val day: Int,
        val month: Int,
        override val year: String,
    ) : DateWhen

    data class Month(
        val month: Int,
        override val year: String,
    ) : DateWhen

    data class Year(
        override val year: String,
    ) : DateWhen

    companion object {
        /** [date] (`2014`, `2014-07`, `2014-07-29`), or null when it has no year. */
        fun of(date: String): DateWhen? {
            val year = ReleaseDate.yearIn(date) ?: return null
            val parts = date.split('-')
            val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..MONTHS }
            val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..DAYS }
            return when {
                month == null -> Year(year)
                day == null -> Month(month, year)
                else -> Day(day, month, year)
            }
        }

        private const val MONTHS = 12
        private const val DAYS = 31
    }
}

// The album the player names and what the album line shows of it: its year, and of the releases its
// name names in that year, the earliest date and their release groups.
private class AlbumDates(
    val name: String?,
    val year: String?,
    val date: String?,
    val groups: Set<String>,
) {
    /**
     * Whether [first] is this album: a known release group decides (a single and a later album of
     * one title are not one release); only without one on either side does the title, as
     * [NameMatcher.albumKey] reads it.
     */
    fun has(first: FirstRelease): Boolean =
        name != null &&
            if (first.releaseGroupMbid != null && groups.isNotEmpty()) {
                first.group in groups
            } else {
                ALBUMS.albumKey(first.title) == ALBUMS.albumKey(name)
            }

    companion object {
        fun of(
            track: TrackState?,
            header: NowPlaying,
        ): AlbumDates {
            val kept = keptMusicBrainz(track)
            val dates = kept?.releaseDates?.ifEmpty { null } ?: track?.resolution?.releaseDates.orEmpty()
            val name = header.album?.takeIf(String::isNotBlank)
            val year = name?.let { ReleaseDate.albumYear(it, dates, header.year?.toString(), ALBUMS::albumKey) }
            val inYear =
                if (name == null || year == null) {
                    emptyList()
                } else {
                    ReleaseDate
                        .named(
                            name,
                            dates,
                            ALBUMS::albumKey,
                        ).filter { it.date?.let(ReleaseDate::yearIn) == year }
                }
            return AlbumDates(name, year, earliest(inYear.mapNotNull { it.date }), inYear.map { it.group }.toSet())
        }

        // The earliest of [dates], as precise as the dates that refine it allow: of `2014` and
        // `2014-07-29`, the day.
        private fun earliest(dates: List<String>): String? =
            generateSequence(dates.minOrNull()) { at ->
                dates.filter { it.length > at.length && it.startsWith(at) }.minOrNull()
            }.lastOrNull()
    }
}

// MusicBrainz's result for [track], as the run gave it or the retrieval cache kept it.
private fun keptMusicBrainz(track: TrackState?): SourceResult? =
    (track?.sources?.get(SourceId.MUSICBRAINZ) as? SourceState.Done)?.result

// A first release's group as [ReleaseDate.group] has it: the release group's id, else the release's own.
private val FirstRelease.group: String get() = releaseGroupMbid ?: releaseMbid

// Album names compared as sources write them differently, "Vol." as "Volume" ([NameMatcher.albumKey]).
private val ALBUMS = NameMatcher(JvmAccentFolder)
