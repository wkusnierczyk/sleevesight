package io.github.wkusnierczyk.sleevesight.core.resolution

/**
 * What resolution asks MusicBrainz (`design/architecture.md`, *Entity resolution*): `:core` holds
 * the scoring and decisions over this interface, and `:sources` implements it over `:net`
 * (`MusicBrainzClient`). The types are the parts of MusicBrainz's answers resolution uses, nothing
 * more; tags and genres are never among them (`architecture.md`, *MusicBrainz tags and genres*).
 */
interface MusicBrainzLookup {
    /** Recordings matching [title] by [artist], as MusicBrainz's search ranks them. */
    suspend fun searchRecordings(
        title: String,
        artist: String,
    ): Lookup<List<MbRecording>>

    /** The recording [mbid], with its work, artist and URL relationships. */
    suspend fun recording(mbid: String): Lookup<MbRecordingDetail>

    /** The work [mbid], with its writers, URL relationships and recordings. */
    suspend fun work(mbid: String): Lookup<MbWork>

    /**
     * One page of the artists whose name matches [name], as MusicBrainz's search ranks them,
     * starting at [offset], each with its own name (not an alias). The search matches words, so the
     * answer holds longer names too ("Reem Kelani" for "Reem"), and does not always rank the exact
     * name first.
     */
    suspend fun searchArtists(
        name: String,
        offset: Int = 0,
    ): Lookup<MbArtistSearch>
}

/**
 * The answer of a client in `:core`'s own terms, since `:core` cannot see `:net`'s: found, known
 * not to exist, or not obtained.
 */
sealed interface Lookup<out T> {
    data class Found<out T>(
        val value: T,
    ) : Lookup<T>

    /** The source answered that there is no such thing. */
    data object NotFound : Lookup<Nothing>

    /** The source could not be asked or did not answer usably. [reason] names the kind, never a URL or a value. */
    data class Failed(
        val reason: String,
    ) : Lookup<Nothing>
}

/**
 * One name in an artist credit: [name] as credited, which can differ from the artist's own
 * [artistName], and the [joinPhrase] that follows it (` feat. `, ` & `, or empty for the last).
 */
data class MbCredit(
    val name: String,
    val artistMbid: String,
    val artistName: String,
    val joinPhrase: String = "",
)

/** A release group, the release's album, single or EP across its editions. */
data class MbReleaseGroup(
    val mbid: String,
    val title: String,
    val primaryType: String?,
)

/** A release a recording appears on. [date] is null when MusicBrainz has none. */
data class MbRelease(
    val mbid: String,
    val title: String,
    val date: String?,
    val releaseGroup: MbReleaseGroup?,
)

/**
 * A recording as the search returns it.
 *
 * @property lengthMs null when MusicBrainz has no length.
 * @property score the search's own relevance, 0 to 100.
 */
data class MbRecording(
    val mbid: String,
    val title: String,
    val lengthMs: Long?,
    val credits: List<MbCredit>,
    val firstReleaseDate: String?,
    val releases: List<MbRelease>,
    val score: Int,
)

/** An artist as the artist search returns it: its id and its own name. */
data class MbArtist(
    val mbid: String,
    val name: String,
)

/**
 * One page of an artist search: [artists], and [total], how many artists match in all. The search
 * is complete once the pages fetched hold [total] artists.
 */
data class MbArtistSearch(
    val total: Int,
    val artists: List<MbArtist>,
)

/** A relationship between a recording or work and an artist; [type] is MusicBrainz's (`producer`, `vocal`). */
data class MbArtistRelation(
    val type: String,
    val artistMbid: String,
    val artistName: String,
    val attributes: List<String>,
)

/** A recording's performance of a work. [cover] when MusicBrainz marks the performance as one. */
data class MbWorkLink(
    val mbid: String,
    val title: String,
    val cover: Boolean,
)

/** A recording looked up by id, with its relationships. */
data class MbRecordingDetail(
    val mbid: String,
    val title: String,
    val credits: List<MbCredit>,
    val firstReleaseDate: String?,
    val works: List<MbWorkLink>,
    val artistRelations: List<MbArtistRelation>,
    val wikidataIds: List<String>,
)

/**
 * A recording of a work, as the work's relationships list it.
 *
 * @property date the relationship's begin date (when it was recorded), as MusicBrainz gives it.
 * @property attributes MusicBrainz's attributes of the performance (`cover`, `live`, `instrumental`).
 */
data class MbPerformance(
    val recordingMbid: String,
    val title: String,
    val credits: List<MbCredit>,
    val date: String?,
    val attributes: List<String>,
)

/** A work looked up by id. */
data class MbWork(
    val mbid: String,
    val title: String,
    val artistRelations: List<MbArtistRelation>,
    val wikidataIds: List<String>,
    val performances: List<MbPerformance>,
)
