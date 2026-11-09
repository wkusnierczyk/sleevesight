package app.sleevesight.app.ui.home

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.EditionYear
import app.sleevesight.core.normalizer.YearTag
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.MbGroupFirst
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.ReleaseType
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.text.DateWhen
import app.sleevesight.core.normalizer.Edition as EditionNames

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

/**
 * The year the album line shows after [album] (#1116): [year], unless the album's name already gives
 * one of its edition, a remaster's or an edition's ([EditionYear.of]: "Material Girl (2024
 * Remaster)"), not before [year] (a year before it is the name's own: "Paris 1919 Remastered", 1973).
 */
internal fun albumLineYear(
    album: String,
    year: String?,
): String? = year?.takeUnless { shown -> EditionYear.of(album)?.let { it.year >= shown } == true }

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
     * one, as [kind] names it (#1058). Only for the group's first release, played as it is: a later
     * edition of it, or a later remaster its name gives, is an [Edition] (#1099, #1116).
     */
    data class Album(
        val date: String,
        val kind: ReleaseKind? = null,
    ) : DateSentence

    /**
     * An edition of an album (#1099, #1116): the original first, "The album {title} was first
     * released {when}." ([title] and [firstDate], its release group's first release; "The single …"
     * or "The EP …" as [kind] names it, as [Album]'s does), then the edition played, as [edition]
     * says it.
     */
    data class Edition(
        val firstDate: String,
        val title: String,
        val edition: EditionPart,
        val kind: ReleaseKind? = null,
    ) : DateSentence

    /**
     * "This track was first released on the original {title} album.": its first release is the
     * original of the edition an [Edition] sentence says, whose date that says (#1116).
     */
    data class FirstOriginal(
        val title: String,
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
     * "This track was first released on the album {album} {when}." when MusicBrainz names it an
     * [album] (#1116), on the single or EP [kind] names, else "… on {album} {when}."; with no [title]
     * known, "This track was first released {when}.".
     */
    data class First(
        val date: String,
        val title: String?,
        val kind: ReleaseKind?,
        val album: Boolean = false,
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
 * How an [DateSentence.Edition] says the edition played, after its original (#1116), each [date] the
 * edition's as MusicBrainz gives it.
 */
internal sealed interface EditionPart {
    /** "This edition was released {when}.": under the original's title, their editions aside. */
    data class Same(
        val date: String,
    ) : EditionPart

    /** "{name} was released {when}.": under another title, the player's [name]. */
    data class Named(
        val name: String,
        val date: String,
    ) : EditionPart

    /**
     * "This is a {tag} of {name}, first released {when}.": a remaster or an edition the player's
     * name gives ([tag]), later than the edition [name] that MusicBrainz dates (the player's name
     * less [tag]).
     */
    data class Remaster(
        val tag: YearTag,
        val name: String,
        val date: String,
    ) : EditionPart

    /**
     * "This is its {tag}.": a remaster or an edition the player's name gives ([tag]), later than any
     * release MusicBrainz has of the original, or of its edition under the original's title.
     */
    data class Its(
        val tag: YearTag,
    ) : EditionPart
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
 * The album's sentence says "originally" only of its release group's first release, played as it
 * is: of a later edition, or of one under another title, it says the group's first release and the
 * edition's ([DateSentence.Edition], #1099), as MusicBrainz's lookup of the group gives them
 * ([firsts], by group, [editionGroup]'s); while it has not answered, the album's sentence is as
 * before. A remaster or edition the player's album names with a year later than the release dated
 * ([EditionYear.of]) is said as such (#1116): of that edition, or, when MusicBrainz has no
 * release of it, "This is its {tag}." after the original. After an edition, a track first released
 * on its original album says so with no date ([DateSentence.FirstOriginal]); on a single or an EP,
 * with its date.
 *
 * From MusicBrainz's result, which keeps them in the retrieval cache, else the run's resolution,
 * as the album's year is ([headerYears]). A result kept before #724 has the first release's date
 * only, and no original: the track's sentence says the date alone, and there is no cover sentence.
 */
internal fun homeDates(
    track: TrackState?,
    header: NowPlaying,
    firsts: Map<String, MbGroupFirst> = emptyMap(),
): List<DateSentence> {
    val kept = keptMusicBrainz(track)
    val resolution = track?.resolution
    val album = AlbumDates.of(track, header)
    val sentences = mutableListOf<DateSentence>()
    val albumSentence = album.sentence(firsts)
    albumSentence?.let { sentences += it }
    val first = firstReleaseOf(track)
    val firstDate = first?.date ?: kept?.firstReleased ?: resolution?.firstReleaseDate
    trackSentence(album, albumSentence, first, firstDate)?.let { sentences += it }
    // A fresh lookup's original wins over a kept one without its release (#724): a *Look again*
    // that finds the release a failed lookup missed keeps the earlier state, which lacks it.
    val cover = kept?.original?.takeIf { it.release != null } ?: resolution?.original ?: kept?.original
    if (cover != null && DateWhen.of(cover.date) != null) {
        val release = cover.release
        sentences +=
            DateSentence.Original(cover.date, cover.artistCredit, release?.title, ReleaseKind.of(release?.type))
    }
    return sentences
}

// The track's first release's sentence ([homeDates]), its [first] release dated [firstDate]: "this
// album" when it is the album played, said as the group's first release ([albumSentence]); after an
// edition's sentence, where "this album" would read as the group's first release (#1099), the
// original album named, a single or an EP with its date and title (#1116); else with its date.
private fun trackSentence(
    album: AlbumDates,
    albumSentence: DateSentence?,
    first: FirstRelease?,
    firstDate: String?,
): DateSentence? {
    val original =
        (albumSentence as? DateSentence.Edition)
            ?.takeIf { first != null && it.kind == null && album.isOriginal(first, it.firstDate) }
            ?.title
    return when {
        first != null && albumSentence is DateSentence.Album && album.has(first) -> DateSentence.FirstHere(album.kind)
        original != null -> DateSentence.FirstOriginal(original)
        firstDate != null && DateWhen.of(firstDate) != null ->
            DateSentence.First(firstDate, first?.title, ReleaseKind.of(first?.type), first?.type == ALBUM_TYPE)
        else -> null
    }
}

/**
 * The release group whose first release *Dates* asks MusicBrainz for (#1099): the one group of the
 * releases whose date the album's sentence says ([homeDates]), when it says one; null otherwise,
 * when nothing is asked.
 */
internal fun editionGroup(
    track: TrackState?,
    header: NowPlaying,
): String? = AlbumDates.of(track, header).group

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
// earliest date, their release groups, their type when all are singles or EPs (#1058), and the title
// of the one whose date is said (#1099).
private class AlbumDates(
    val name: String?,
    val year: String?,
    val date: String?,
    val groups: Set<String>,
    val kind: ReleaseKind?,
    val title: String? = null,
) {
    // The one release group of the releases whose [date] is said, when it is said (#1099).
    val group: String? get() = groups.singleOrNull()?.takeIf { date != null }

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

    /**
     * Whether [first] is the original album of an edition first released on [firstDate] (#1116):
     * in one of its release groups, out in [firstDate]'s year, or in an unknown year, as [has] reads
     * a year.
     */
    fun isOriginal(
        first: FirstRelease,
        firstDate: String,
    ): Boolean =
        first.releaseGroupMbid != null &&
            first.group in groups &&
            ReleaseDate.yearIn(first.date).let { it == null || it == ReleaseDate.yearIn(firstDate) }

    /**
     * The album's sentence, when its [date] is known (#1099): of a later edition of its release
     * group, or one under another title, the group's first release and the edition's
     * ([DateSentence.Edition]), as MusicBrainz's lookup of [group] gives them ([firsts], by group);
     * else, a later remaster or edition the player's name gives, after the release dated as the
     * original ([remasterOf], #1116); else, and while the group is not known, "originally"
     * ([DateSentence.Album]).
     */
    fun sentence(firsts: Map<String, MbGroupFirst>): DateSentence? =
        date?.let { date ->
            val first = group?.let { firsts[it] }
            first?.let { editionOf(it, date) }
                ?: remasterOf(first, date)
                ?: DateSentence.Album(date, kind)
        }

    // The player's name's remaster or edition ([EditionYear.of]) when its year is after
    // [date]'s: one MusicBrainz has no release of, or dates from an earlier release.
    private fun laterTag(date: String): YearTag? {
        val year = ReleaseDate.yearIn(date) ?: return null
        return name?.let(EditionYear::of)?.takeIf { it.year > year }
    }

    // A later remaster or edition the player's name gives of the release dated [date], said as the
    // original: "The single Material Girl was first released in 1985. This is its 2024 remaster."
    // Named by its group's title when MusicBrainz's lookup of it ([first]) dates the group in
    // [date]'s year, else by the release's, else by the player's name less the tag. Null with no
    // such tag.
    private fun remasterOf(
        first: MbGroupFirst?,
        date: String,
    ): DateSentence.Edition? {
        val tag = laterTag(date) ?: return null
        val group = first?.takeIf { it.firstReleaseDate?.let(ReleaseDate::yearIn) == ReleaseDate.yearIn(date) }
        return DateSentence.Edition(date, group?.title ?: title ?: tag.name, EditionPart.Its(tag), kind)
    }

    // The edition's sentence of the release dated [date], when [first], its group as MusicBrainz's
    // lookup gives it, was first released in an earlier year (an original played in its own year is
    // not an edition of a pressing out days before it), or as early under another title than [title],
    // their editions aside ([EditionNames.stripEditions]: "Rumours (Remastered)" is "Rumours"). The
    // edition by the player's name when its title is another, as a later remaster or edition when
    // the name gives one ([laterTag], #1116): of the edition by the name less it, or, under the
    // original's title, as its own. Null when the group gives no readable date, or one after [date]
    // (which MusicBrainz should not: then nothing is said that it does not say).
    private fun editionOf(
        first: MbGroupFirst,
        date: String,
    ): DateSentence.Edition? {
        val firstDate = first.firstReleaseDate?.takeIf { ReleaseDate.yearIn(it) != null } ?: return null
        val firstYear = ReleaseDate.yearIn(firstDate).orEmpty()
        val another = title != null && !sameTitle(first.title, title)
        val earlier = ReleaseDate.yearIn(date)?.let { firstYear < it } == true
        val tag = laterTag(date)
        val edition =
            when {
                tag != null && sameTitle(first.title, tag.name) -> EditionPart.Its(tag)
                tag != null -> EditionPart.Remaster(tag, tag.name, date)
                another && name != null -> EditionPart.Named(name, date)
                else -> EditionPart.Same(date)
            }
        return DateSentence.Edition(firstDate, first.title, edition, kind).takeIf {
            earlier || (another && !before(date, firstDate))
        }
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
            val groups = album.map { it.group }.toSet()
            val title = album.firstOrNull { it.date == date }?.title
            return AlbumDates(name, year, date, groups, kindNamed(album, date), title)
        }

        // Whether [a] and [b] are one title, their bracketed editions aside, as the album's key reads them.
        private fun sameTitle(
            a: String,
            b: String,
        ): Boolean = ALBUMS.albumKey(EditionNames.stripEditions(a)) == ALBUMS.albumKey(EditionNames.stripEditions(b))

        // Whether [a] is before [b], at the precision both have: `1987` is not before `1987-09-07`.
        private fun before(
            a: String,
            b: String,
        ): Boolean {
            val n = minOf(a.length, b.length)
            return a.take(n) < b.take(n)
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
        val sentence =
            if (here) {
                DateSentence.FirstHere()
            } else {
                DateSentence.First(first.date, title, kindOf(first.type), first.type == ReleaseType.ALBUM)
            }
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

// MusicBrainz's primary type of an album, which *Dates* names as one (#1116).
private const val ALBUM_TYPE = "Album"

// Album names compared as sources write them differently, "Vol." as "Volume" ([NameMatcher.albumKey]).
private val ALBUMS = NameMatcher(JvmAccentFolder)
