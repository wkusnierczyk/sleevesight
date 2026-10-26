package app.sleevesight.core.sources

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.RecordingTags
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import kotlinx.coroutines.Deferred
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One source of evidence (`design/architecture.md`, *Source adapters*). Implementations live in
 * `:sources`, one per source, with their evidence extraction (D1).
 *
 * Per D4 an adapter looks up by name at once and awaits [Resolution] only to verify its match
 * against the resolved ids, so a slow MusicBrainz delays only its own lines. A match that cannot
 * be verified stays unverified (shown, not cited). Web search is the exception: a web page has no
 * id to verify against, so its results are kept or dropped by `RecordingFilter` alone, and the kept
 * ones may be cited (`ExaWebSearch`).
 *
 * The pipeline always completes the `Deferred<Resolution>` normally: with [Resolution.NONE] when
 * resolution fails, is switched off or is cancelled, never exceptionally, so `await()` never
 * throws into an adapter and one failing source cannot take the others down. The resolution
 * (#36) and the orchestration (#44) enforce it.
 */
interface SourceAdapter {
    val id: SourceId

    /**
     * The version of this adapter's evidence extraction. The retrieval cache keeps results, not
     * responses (`:store`), so a change to what an adapter makes of a response (which lines, their
     * wording, the verification) raises this, and the results cached under the old rules are
     * missed instead of served.
     */
    val extractionVersion: Int get() = 1

    /**
     * The sources this adapter asks, which its result depends on even when it holds none of their
     * lines: the Wikipedia adapter's rejection comes from Wikidata's identity check. The retrieval
     * cache keeps a result for the shortest lifetime among these and the sources of its lines.
     */
    val consults: Set<SourceId> get() = setOf(id)

    /**
     * Whether this adapter looks up the next track ahead, from the player's queue item (D13,
     * `Pipeline.prepare`). A queue item has no length, so a result looked up ahead would be kept
     * under a key the playing track never reads, and the requests made for it sent for nothing.
     * Only an adapter that sends no request of its own prepares ahead: MusicBrainz's lines, made
     * from the resolution, which name the artists for the genre labels. (Last.fm's lookup ahead
     * sends its own requests; since #238 the artist cache would keep their answers for when the
     * track plays, so whether it should prepare ahead is open.)
     */
    val preparesAhead: Boolean get() = false

    /**
     * Looks [track] up. Lines in the result must come in a deterministic order for the same
     * response, since [app.sleevesight.core.evidence.EvidenceSet] keeps it
     * when assigning ids. Each draft names its own source, which decides its id; an adapter may
     * make lines for a source besides [id], as the Wikipedia adapter does for Wikidata (`WD`).
     */
    suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult
}

/**
 * Added to an adapter's [SourceAdapter.extractionVersion] while no genre information is asked for
 * (*Show genres* off, #444): its results then hold no tags, so the retrieval cache keeps them apart
 * from those made with them, and neither is served for the other.
 */
const val WITHOUT_GENRES_VERSION: Int = 1_000

/** The prefix of the evidence ids of this adapter's own source, [SourceAdapter.id]. */
val SourceAdapter.tag: String get() = id.tag

/**
 * What one lookup gave: the typed outcome, the evidence lines without ids, and how long it took.
 *
 * [evidence] holds the lines of a verified match, or of a web search result its filter kept (no id
 * to verify against): they get ids and may be cited. [unverified] holds the lines of a match that
 * could not be verified against the [Resolution] (D4): shown, never cited, so never given to the
 * model. A match that failed the check gives neither, and an [Outcome.Rejected]. The default is
 * empty, so a line is cited only when an adapter puts it in [evidence].
 *
 * [outcome] says what the source found, not whether it was verified: [Outcome] has no unverified
 * case, so a result whose lines are all in [unverified] (and none in [evidence]) is the unverified
 * one, whatever its outcome. [rejections] keeps each candidate that failed the check, with its
 * reason, also when another candidate gave lines (one credited artist of several), so the
 * *Sources* section can show them.
 *
 * [genres] are the genre labels the source gives the track's artists (`design/architecture.md`,
 * *Genre labels*), each naming its artist ([GenreLabel.artist]): Last.fm's artist tags through the
 * vocabulary, from a verified or unverified match only; empty for a source that gives none. They
 * are never evidence, so never in a prompt. The track's and the album's labels (#439) are not
 * here: Last.fm's are asked for after the evidence, by the album too, which the retrieval cache's
 * key does not hold.
 *
 * [labelArtists] are the credited artists, with their MusicBrainz ids, whose tags give
 * MusicBrainz's genre labels (#84): MusicBrainz's result holds the resolved artists, kept with it
 * in the retrieval cache, so a cached track gets its labels too. The pipeline asks for their tags
 * after the evidence is assembled, so the insight does not wait for them. [recordingTags] are the
 * accepted recording's tags (#439), which the resolution's recording lookup brought, for the
 * track's labels; kept the same way, and never in a line.
 *
 * [firstReleased] is the date of the recording's first release, as MusicBrainz's resolution gives
 * it (`Resolution.firstReleaseDate`), on MusicBrainz's result only: kept with it in the retrieval
 * cache, so a track served from the cache, which makes no resolution, still shows its year.
 * [releaseDates] are the titles the releases of the track's recordings go by, with their dates
 * (`Resolution.releaseDates`, #556), kept the same way, for the year after the album the player
 * names: the cache's key does not hold the album, so the album is found among them when shown.
 *
 * [provisional] marks a result that a failed request of the verification left unverified, where a
 * later lookup may verify it (Last.fm's search for other MusicBrainz artists of a name): it is
 * shown, but not kept in the retrieval cache.
 *
 * [freshUntil] is set on a result built from artist-level parts kept from an earlier track
 * ([ArtistResults], #238): the earliest time one of them stops being fresh. The retrieval cache
 * keeps the result no longer than that, so a reused part is not served past its own lifetime
 * (D9). Null when no kept part was reused. Not itself kept: the retrieval entry's age carries it.
 *
 * [trackOnly] marks a result known to say nothing about the track's artists: no artist's page,
 * line, tag or id, and no verdict on one, found or not. Only the adapter that built it knows, so it
 * sets it, on the paths that consult no artist (Exa's web search; Wikipedia with no artist to look
 * up; MusicBrainz finding no recording). While *Keep what sources said about each artist* is off
 * (#420), the retrieval cache keeps and reads back only such results: false, the default, counts
 * as about the artists, so a result of a new kind is not kept until its adapter says otherwise.
 */
data class SourceResult(
    val outcome: Outcome,
    val evidence: List<EvidenceDraft>,
    val latency: Duration,
    val unverified: List<EvidenceDraft> = emptyList(),
    val rejections: List<Rejection> = emptyList(),
    val genres: List<GenreLabel> = emptyList(),
    val provisional: Boolean = false,
    val labelArtists: List<ResolvedArtist> = emptyList(),
    val recordingTags: RecordingTags? = null,
    val firstReleased: String? = null,
    val releaseDates: List<ReleaseDate> = emptyList(),
    val freshUntil: Instant? = null,
    val trackOnly: Boolean = false,
)

/**
 * A candidate that failed the entity check: what it was looked up for, [subject], and why it was
 * rejected. [scope] is what the candidate was about: [Scope.ARTIST] for an artist's page (Last.fm's
 * artist, the Wikipedia artist article), so a verdict on an artist is kept only as what was said
 * about the artist is (#420); [Scope.SONG] for the song's.
 */
data class Rejection(
    val subject: String,
    val reason: String,
    val scope: Scope,
)
