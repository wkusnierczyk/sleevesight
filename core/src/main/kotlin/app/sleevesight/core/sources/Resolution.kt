package app.sleevesight.core.sources

import app.sleevesight.core.model.Outcome

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
 * @property firstRelease the earliest release of the recording; the player's release stays
 *   separate (`P` evidence).
 * @property releaseDates the titles the releases of the track's recordings go by, each with the
 *   earliest date MusicBrainz gives a release of that title (#556): of every recording whose title
 *   and length fit and that credits the track's artists, not only the accepted one, since a
 *   soundtrack or compilation often has a recording of its own. The home screen finds the album
 *   the player names among them, for the year after it ([ReleaseDate.yearOf]). Empty when no
 *   recording was accepted.
 * @property work the work the recording performs, when MusicBrainz links one.
 * @property original the work's earliest dated recording by another artist, when this recording
 *   is a cover or version (step 4) and that recording predates every dated one by the track's
 *   artists; null otherwise. MusicBrainz leaves many recordings undated, so it is the earliest
 *   known, not necessarily the first.
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
 * A title the releases of a track's recordings go by (a release's own, or its release group's), and
 * the earliest [date] MusicBrainz gives a release of that title, at the precision it has (#556).
 */
data class ReleaseDate(
    val title: String,
    val date: String,
) {
    companion object {
        /**
         * The year of the release [album] names, as the player reports it, among [dates]: the year
         * of the earliest release whose title has [album]'s [key] (the same title, as sources write
         * it differently), or null when none has it or its date has no year. The title must be
         * the same: an album named with an edition the releases do not name ("Crazy World
         * (Remastered 2009)") is not found, rather than given another edition's year.
         */
        fun yearOf(
            album: String,
            dates: List<ReleaseDate>,
            key: (String) -> String,
        ): String? {
            val wanted = key(album).takeIf { it.isNotEmpty() } ?: return null
            return dates
                .filter { key(it.title) == wanted }
                .minOfOrNull { it.date }
                ?.let(::yearIn)
        }

        /** The year [date] begins with (`1974` of `1974-03-25`), or null when it has none. */
        fun yearIn(date: String): String? =
            date.take(YEAR_LENGTH).takeIf { it.length == YEAR_LENGTH && it.all(Char::isDigit) }

        private const val YEAR_LENGTH = 4
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
 */
data class OriginalRecording(
    val recordingMbid: String,
    val title: String,
    val artists: List<ResolvedArtist>,
    val artistCredit: String,
    val date: String,
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
