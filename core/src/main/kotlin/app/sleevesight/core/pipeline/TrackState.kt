package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.GroundedText

/**
 * Everything the UI renders for one track (`design/architecture.md`, *State*), immutable: the
 * pipeline publishes a new one as each stage lands.
 *
 * @property sources one entry per adapter, by its source: [SourceState.Pending] until it lands.
 *   A source the user switched off is [SourceState.Done] with `Outcome.SourceOff`.
 * @property resolution what MusicBrainz resolved, once it has (null before, and when every source
 *   was served from the cache, so nothing needed it).
 * @property evidence the cited evidence, with ids and fingerprint, once every source has landed.
 * @property genres the artists' genre labels, from the label sources switched on, combined
 *   ([app.sleevesight.core.genres.combineLabels]), once every source has landed.
 */
data class TrackState(
    val nowPlaying: NowPlaying,
    val normalized: NormalizedTrack,
    val sources: Map<SourceId, SourceState>,
    val resolution: Resolution? = null,
    val evidence: EvidenceSet? = null,
    val genres: List<TrackLabel> = emptyList(),
    val insight: InsightState = InsightState.Idle(canWrite = false),
) {
    /** Whether every source has landed. */
    val gathered: Boolean get() = sources.values.none { it is SourceState.Pending }
}

/** One source's progress for the current track. */
sealed interface SourceState {
    /** Asked, not landed yet. */
    data object Pending : SourceState

    /**
     * Landed: the adapter's [result], served from the retrieval cache when [cached] (its latency is
     * then the original lookup's).
     */
    data class Done(
        val result: SourceResult,
        val cached: Boolean,
    ) : SourceState

    /**
     * The adapter failed in a way it did not map to an outcome (a bug, not the network, which is
     * [app.sleevesight.core.model.Outcome.Unreachable]). [reason] is the
     * exception's type only, never its message, which could quote a response or a request.
     */
    data class Failed(
        val reason: String,
    ) : SourceState
}

/** The home insight's progress (`design/architecture.md`, *State*; *Errors and empty states*). */
sealed interface InsightState {
    /**
     * Not written. [canWrite] is false when insights are off, no AI service is set up, or the
     * evidence is still being gathered; true when a tap on Write would write it.
     */
    data class Idle(
        val canWrite: Boolean,
    ) : InsightState

    /** The model is writing; [partial] is the text so far, not yet checked. */
    data class Writing(
        val partial: String,
    ) : InsightState

    /**
     * Written and checked. [cached] when served from the synthesis cache, with no call; [usage] is
     * the call's, null when cached. [complete] is false when the reply was cut off at the token
     * limit: shown, but not kept.
     */
    data class Written(
        val grounded: GroundedText,
        val cached: Boolean,
        val usage: Usage? = null,
        val complete: Boolean = true,
    ) : InsightState

    /**
     * Nothing usable to write from: the evidence had nothing to narrate
     * (`EvidenceSet.nothingToNarrate`), so no call was made, or the model replied `NO_EVIDENCE`. The
     * card shows the app's own text.
     */
    data object NoEvidence : InsightState

    /** The call failed, or the model declined; [reason] is a fixed label for the card and the log. */
    data class Failed(
        val reason: String,
    ) : InsightState

    /** The user stopped the call before its reply ended: nothing is kept, and Write may be offered again. */
    data object Stopped : InsightState
}
