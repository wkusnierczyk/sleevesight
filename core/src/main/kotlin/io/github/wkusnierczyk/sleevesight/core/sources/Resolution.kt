package io.github.wkusnierczyk.sleevesight.core.sources

import io.github.wkusnierczyk.sleevesight.core.model.Outcome

/**
 * What entity resolution established about a track, which adapters verify their matches against
 * (`design/architecture.md`, *Entity resolution*, steps 1 to 4). Built by
 * [io.github.wkusnierczyk.sleevesight.core.resolution.RecordingResolver] from MusicBrainz; every
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
 */
data class Resolution(
    val outcome: Outcome = Outcome.None,
    val recordingMbid: String? = null,
    val title: String? = null,
    val artistCredit: String? = null,
    val artists: List<ResolvedArtist> = emptyList(),
    val firstRelease: FirstRelease? = null,
    val work: ResolvedWork? = null,
    val original: OriginalRecording? = null,
    val cover: Boolean = false,
    val earliest: Boolean = false,
    val credits: List<Credit> = emptyList(),
    val wikidataIds: List<String> = emptyList(),
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

        /** MusicBrainz could not be asked. */
        val UNREACHABLE: Resolution = Resolution(outcome = Outcome.Unreachable)
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
