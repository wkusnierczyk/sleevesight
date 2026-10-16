package app.sleevesight.core.sources

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import kotlinx.coroutines.Deferred
import kotlin.time.Duration

/**
 * One source of evidence (`design/architecture.md`, *Source adapters*). Implementations live in
 * `:sources`, one per source, with their evidence extraction (D1).
 *
 * Per D4 an adapter looks up by name at once and awaits [Resolution] only to verify its match
 * against the resolved ids, so a slow MusicBrainz delays only its own lines. A match that cannot
 * be verified stays unverified (shown, not cited).
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

/** The prefix of the evidence ids of this adapter's own source, [SourceAdapter.id]. */
val SourceAdapter.tag: String get() = id.tag

/**
 * What one lookup gave: the typed outcome, the evidence lines without ids, and how long it took.
 *
 * [evidence] holds the lines of a verified match: they get ids and may be cited. [unverified] holds
 * the lines of a match that could not be verified against the [Resolution] (D4): shown, never
 * cited, so never given to the model. A match that failed the check gives neither, and an
 * [Outcome.Rejected]. The default is empty, so a line is cited only when an adapter puts it in
 * [evidence].
 *
 * [outcome] says what the source found, not whether it was verified: [Outcome] has no unverified
 * case, so a result whose lines are all in [unverified] (and none in [evidence]) is the unverified
 * one, whatever its outcome. [rejections] keeps each candidate that failed the check, with its
 * reason, also when another candidate gave lines (one credited artist of several), so the
 * *Sources* section can show them.
 *
 * [genres] are the genre labels the source gives the track's artists (`design/architecture.md`,
 * *Genre labels*): Last.fm's tags through the vocabulary, from a verified or unverified match only;
 * empty for a source that gives none. They are never evidence, so never in a prompt.
 *
 * [labelArtists] are the MusicBrainz ids of the credited artists whose tags give MusicBrainz's
 * genre labels (#84): MusicBrainz's result holds the resolved artists, kept with it in the
 * retrieval cache, so a cached track gets its labels too. The pipeline asks for their tags after
 * the evidence is assembled, so the insight does not wait for them.
 *
 * [provisional] marks a result that a failed request of the verification left unverified, where a
 * later lookup may verify it (Last.fm's search for other MusicBrainz artists of a name): it is
 * shown, but not kept in the retrieval cache.
 */
data class SourceResult(
    val outcome: Outcome,
    val evidence: List<EvidenceDraft>,
    val latency: Duration,
    val unverified: List<EvidenceDraft> = emptyList(),
    val rejections: List<Rejection> = emptyList(),
    val genres: List<GenreLabel> = emptyList(),
    val provisional: Boolean = false,
    val labelArtists: List<String> = emptyList(),
)

/** A candidate that failed the entity check: what it was looked up for, [subject], and why it was rejected. */
data class Rejection(
    val subject: String,
    val reason: String,
)
