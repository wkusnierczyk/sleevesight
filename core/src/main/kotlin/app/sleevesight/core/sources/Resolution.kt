package app.sleevesight.core.sources

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Edition

/**
 * What entity resolution established about a track, which adapters verify their matches against
 * (`design/architecture.md`, *Entity resolution*, steps 1 to 4). Built by
 * [app.sleevesight.core.resolution.RecordingResolver] from MusicBrainz; every
 * field is null or empty when nothing was resolved.
 *
 * @property outcome MusicBrainz's own outcome for the track: [Outcome.SongArticle] when a
 *   recording was accepted, [Outcome.None], [Outcome.Rejected] for a near miss with a conflicting
 *   artist, [Outcome.Unreachable] when MusicBrainz could not be asked. Never `SourceOff`: the
 *   pipeline does not resolve when the source is off, and passes [NONE].
 * @property recordingMbid the accepted MusicBrainz recording.
 * @property title the accepted recording's title, as MusicBrainz writes it.
 * @property artistCredit the accepted recording's artist credit, as MusicBrainz writes it
 *   (`Sonny Boy Williamson with Big Joe Williams`).
 * @property artists each credited artist of the track, in the track's order, resolved separately:
 *   its MusicBrainz artist id when the accepted recording credits it (or names it in a performer
 *   relationship), null otherwise. Empty when no recording was accepted.
 * @property jointArtists the artists of a joint credit a track's artist was accepted under (#661:
 *   the player's "Derya Yıldırım & Grup Şimşek" is MusicBrainz's "Derya Yıldırım" and "Grup Şimşek"),
 *   by their credited names, with their MusicBrainz ids, for the genre labels only: the track's artist
 *   itself has no id in [artists], since no one MusicBrainz artist is theirs. Empty otherwise.
 * @property firstRelease the earliest release of the recording; the player's release stays
 *   separate (`P` evidence).
 * @property releaseDates the releases of the track's recordings, one per title and release group
 *   with its earliest date ([ReleaseDate.collapse], #556): of every recording the resolution would
 *   accept, whose title and length fit and whose credits are the accepted artists (not a
 *   namesake's), not only the accepted one, since a soundtrack or compilation often has a
 *   recording of its own. The home screen finds the album the player names among them, for the
 *   year after it ([ReleaseDate.albumYear]). Empty when no recording was accepted.
 * @property work the work the recording performs, when MusicBrainz links one.
 * @property original the work's earliest dated recording by another artist, when this recording
 *   is a cover or version (step 4) and that recording predates every dated one by the track's
 *   artists; null otherwise. MusicBrainz leaves many recordings undated, so it is the earliest
 *   known, not necessarily the first. With the release it first came out on, when a lookup of its
 *   own found one ([OriginalRecording.release], #724).
 * @property cover whether this recording is a cover or version of a song first recorded by someone
 *   else: MusicBrainz marks the performance as a cover, or the work has a recording by another
 *   artist dated before every recording of it by the track's artists, all of which are dated.
 * @property earliest whether the track's artists (those the player names, with the bands whose
 *   leader is confirmed as one of them; not a collaborator credited beside them) recorded the work
 *   first, as far as MusicBrainz dates it: their earliest recording of it with a recording date is
 *   before every dated recording of it by anyone else, of which there is at least one, and it is
 *   not a cover. Release dates do not count here, since MusicBrainz gives none for the others'
 *   recordings of a work. An earlier recording cannot be a cover of a later one, so the identity
 *   check takes the work's linked item as the track's own song (#76); undated recordings count for
 *   nothing either way.
 * @property credits the people credited on the recording and the work (writers, producers,
 *   performers), in MusicBrainz's order, for the templated evidence (D1).
 * @property wikidataIds Wikidata items MusicBrainz links to the recording and its work, for the
 *   Wikipedia identity check (step 5), the recording's first.
 * @property recordingTags the accepted recording's MusicBrainz tags
 *   ([app.sleevesight.core.resolution.MbRecordingDetail.tags], #439), for the track's genre labels
 *   only: no line templates them, and no prompt holds them.
 * @property failure why MusicBrainz could not be asked, when [outcome] is [Outcome.Unreachable]:
 *   the failed lookup's kind as the client names it (`RateLimited`, `Offline`, `Server`,
 *   `status 400`, …), never a URL or a value; for the diagnostics only (#191). Null otherwise.
 */
data class Resolution(
    val outcome: Outcome = Outcome.None,
    val recordingMbid: String? = null,
    val title: String? = null,
    val artistCredit: String? = null,
    val artists: List<ResolvedArtist> = emptyList(),
    val jointArtists: List<ResolvedArtist> = emptyList(),
    val firstRelease: FirstRelease? = null,
    val releaseDates: List<ReleaseDate> = emptyList(),
    val work: ResolvedWork? = null,
    val original: OriginalRecording? = null,
    val cover: Boolean = false,
    val earliest: Boolean = false,
    val credits: List<Credit> = emptyList(),
    val wikidataIds: List<String> = emptyList(),
    val recordingTags: List<String> = emptyList(),
    val failure: String? = null,
) {
    /**
     * The earliest first-release date of the recording, as MusicBrainz gives it (`1974`,
     * `1974-03`, `1974-03-25`).
     */
    val firstReleaseDate: String? get() = firstRelease?.date

    /** Whether a recording was accepted. */
    val resolved: Boolean get() = recordingMbid != null

    companion object {
        /** Nothing resolved. */
        val NONE: Resolution = Resolution()

        /** MusicBrainz could not be asked, for the reason [failure] names. */
        fun unreachable(failure: String): Resolution = Resolution(outcome = Outcome.Unreachable, failure = failure)
    }
}

/** A credited artist as the track names it, and its MusicBrainz artist id when resolved. */
data class ResolvedArtist(
    val name: String,
    val mbid: String?,
)

/**
 * The release a recording first appeared on.
 *
 * @property date as MusicBrainz gives it, at the precision it has.
 * @property title the release group's title.
 * @property releaseGroupMbid the release group, when MusicBrainz names it; else [releaseMbid] is the link.
 * @property type the release group's primary type (`Album`, `Single`, `EP`), when known.
 */
data class FirstRelease(
    val date: String,
    val title: String,
    val releaseGroupMbid: String?,
    val releaseMbid: String,
    val type: String?,
)

/**
 * A release of a track's recording, for the year after the album the player names (#556): its own
 * [title] (never its release group's, which an edition does not carry), the [date] MusicBrainz gives
 * it, at the precision it has (null when it gives none: an undated release still says the album is
 * named, so its name is not looked for without an edition), and its release [group] (the release
 * group's id, else the release's own), so a title several release groups share (two compilations
 * named "Love Songs") keeps each one's year. As kept, one per title and release group, with the
 * earliest date of that title in the group ([collapse]): a reissue under the same title in the same
 * group has the group's year. With the release group's primary [type] (`Album`, `Single`, `EP`), when
 * MusicBrainz gives it (null for a release kept before it was, #724), so the home screen tells a
 * title-track single from its album of the same year.
 */
data class ReleaseDate(
    val title: String,
    val date: String?,
    val group: String,
    val type: String? = null,
) {
    companion object {
        /**
         * [releases] one per title, as [key] reads it, and release group: the earliest of them (an
         * undated one only when none is dated), written as it writes the title; ordered by key, then
         * date, undated last, and none whose key is empty.
         */
        fun collapse(
            releases: List<ReleaseDate>,
            key: (String) -> String,
        ): List<ReleaseDate> =
            releases
                .filter { key(it.title).isNotEmpty() }
                .groupBy { key(it.title) to it.group }
                .values
                .map { same -> same.minWith(EARLIEST.thenBy { it.title }) }
                .sortedWith(compareBy<ReleaseDate> { key(it.title) }.then(EARLIEST).thenBy { it.group })

        /**
         * The year after [album], the player's name for it, from [dates] and [playerYear], the year
         * the player sends (already checked as plausible), in this order (design, *Key data*):
         *
         * 1. The releases [album] names: those whose title has [album]'s [key], dated or not; when
         *    none has, those whose title has the key of [album] without its bracketed editions
         *    ([Edition.stripEditions]: "Abbey Road (Remastered)" names "Abbey Road", but "Sundown
         *    (Live)" never names "Sundown"). Their years are one per release group ([yearsOf]); an
         *    undated one gives none.
         * 2. [playerYear], when it is one of those years: the player knows which edition it plays.
         * 3. The one year, when they have one.
         * 4. [playerYear], when the named releases give no year (none named, or only undated ones);
         *    else null.
         * 5. Null when they have several years and [playerYear] is none of them: two releases of one
         *    name in different years, and nothing to tell which is playing.
         */
        fun albumYear(
            album: String,
            dates: List<ReleaseDate>,
            playerYear: String?,
            key: (String) -> String,
        ): String? {
            val years = yearsOf(album, dates, key)
            return when {
                playerYear != null && playerYear in years -> playerYear
                years.size == 1 -> years.single()
                years.isEmpty() -> playerYear
                else -> null
            }
        }

        /**
         * The distinct years, earliest first, of the releases [album] names ([named]); empty when it
         * names no dated one.
         */
        fun yearsOf(
            album: String,
            dates: List<ReleaseDate>,
            key: (String) -> String,
        ): List<String> =
            named(album, dates, key)
                .mapNotNull { it.date?.let(::yearIn) }
                .distinct()
                .sorted()

        /**
         * The releases of [dates] whose title has [album]'s [key], dated or not, else, when none has,
         * those whose title has the key of [album] without its bracketed editions
         * ([Edition.stripEditions]); empty when neither names one.
         */
        fun named(
            album: String,
            dates: List<ReleaseDate>,
            key: (String) -> String,
        ): List<ReleaseDate> {
            fun of(name: String): List<ReleaseDate> {
                val wanted = key(name).takeIf { it.isNotEmpty() } ?: return emptyList()
                return dates.filter { key(it.title) == wanted }
            }
            val stripped = Edition.stripEditions(album)
            return of(album).ifEmpty { if (stripped != album.trim()) of(stripped) else emptyList() }
        }

        /** The year [date] begins with (`1974` of `1974-03-25`), or null when it has none. */
        fun yearIn(date: String): String? =
            date.take(YEAR_LENGTH).takeIf { it.length == YEAR_LENGTH && it.all(Char::isDigit) }

        private const val YEAR_LENGTH = 4

        // The earliest date first, an undated release after every dated one.
        private val EARLIEST: Comparator<ReleaseDate> =
            compareBy<ReleaseDate> { it.date == null }.thenBy { it.date.orEmpty() }
    }
}

/** A MusicBrainz work: the song, apart from any one recording of it. */
data class ResolvedWork(
    val mbid: String,
    val title: String,
)

/**
 * The first recording of a work by an artist other than the track's.
 *
 * @property artistCredit its artist credit, as MusicBrainz writes it.
 * @property date when it was recorded, as MusicBrainz gives it.
 * @property release the release it first came out on, chosen as the track's own first release is
 *   ([Resolution.firstRelease]), from one more lookup of the recording made only for a cover (#724),
 *   for the home screen's *Dates*; null when that lookup failed or found no dated release.
 */
data class OriginalRecording(
    val recordingMbid: String,
    val title: String,
    val artists: List<ResolvedArtist>,
    val artistCredit: String,
    val date: String,
    val release: FirstRelease? = null,
)

/**
 * One person or group credited on a recording or its work.
 *
 * @property detail what MusicBrainz adds to the relationship, such as the instrument or `lead
 *   vocals`, in its order; empty when it adds nothing.
 */
data class Credit(
    val role: CreditRole,
    val name: String,
    val mbid: String,
    val detail: List<String> = emptyList(),
)

/** The roles the templated MusicBrainz evidence names (D1). */
enum class CreditRole {
    WRITER,
    COMPOSER,
    LYRICIST,
    PRODUCER,
    PERFORMER,
}
