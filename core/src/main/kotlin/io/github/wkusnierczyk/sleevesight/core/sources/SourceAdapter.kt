package io.github.wkusnierczyk.sleevesight.core.sources

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
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
     * Looks [track] up. Lines in the result must come in a deterministic order for the same
     * response, since [io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceSet] keeps it
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
 */
data class SourceResult(
    val outcome: Outcome,
    val evidence: List<EvidenceDraft>,
    val latency: Duration,
    val unverified: List<EvidenceDraft> = emptyList(),
    val rejections: List<Rejection> = emptyList(),
)

/** A candidate that failed the entity check: what it was looked up for, [subject], and why it was rejected. */
data class Rejection(
    val subject: String,
    val reason: String,
)

/**
 * What entity resolution established about a track, which adapters verify their matches against
 * (`design/architecture.md`, *Entity resolution*). Built by the MusicBrainz resolution (#36),
 * which may add to it; every field is null or empty when nothing was resolved.
 *
 * @property recordingMbid the accepted MusicBrainz recording.
 * @property artists each credited artist, resolved separately.
 * @property firstReleaseDate the earliest first-release date of the recording's release groups,
 *   as MusicBrainz gives it (`1974`, `1974-03`, `1974-03-25`); the player's release stays separate.
 */
data class Resolution(
    val recordingMbid: String? = null,
    val artists: List<ResolvedArtist> = emptyList(),
    val firstReleaseDate: String? = null,
) {
    companion object {
        /** Nothing resolved. */
        val NONE: Resolution = Resolution()
    }
}

/** A credited artist as the track names it, and its MusicBrainz artist id when resolved. */
data class ResolvedArtist(
    val name: String,
    val mbid: String?,
)
