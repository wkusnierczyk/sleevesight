package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.synthesis.GroundingCounts
import kotlin.time.Duration

/**
 * What the pipeline did, for the diagnostics log (`design/architecture.md`, *Diagnostics*): session
 * input, normalization, each source's outcome and latency, cache hits, model calls and the grounding
 * check's counts. `:app` keeps the last few hundred in memory; `:cli` writes them to its report.
 *
 * Events carry no key, header or response text: counts, outcomes, ids, durations and the track's
 * own names. [key] is the track's lookup key, to tell runs apart.
 */
sealed interface PipelineEvent {
    val key: String?

    /** A track change reached the pipeline, after the debounce. */
    data class Received(
        val nowPlaying: NowPlaying,
    ) : PipelineEvent {
        override val key: String? = null
    }

    data class Normalized(
        val track: NormalizedTrack,
    ) : PipelineEvent {
        override val key: String = track.lookupKey
    }

    /** A newer track cancelled this one's run (`collectLatest`). */
    data class Superseded(
        override val key: String,
    ) : PipelineEvent

    /** MusicBrainz resolution finished; [outcome] is its own. */
    data class Resolved(
        override val key: String,
        val outcome: Outcome,
        val latency: Duration,
    ) : PipelineEvent

    /** A source landed, from the network or, when [cached], the retrieval cache. */
    data class SourceLanded(
        override val key: String,
        val source: SourceId,
        val outcome: Outcome,
        val evidence: Int,
        val unverified: Int,
        val rejections: Int,
        val latency: Duration,
        val cached: Boolean,
    ) : PipelineEvent

    /** An adapter threw; [reason] is the exception's type. */
    data class SourceFailed(
        override val key: String,
        val source: SourceId,
        val reason: String,
    ) : PipelineEvent

    /**
     * A source's result was not kept in the retrieval cache, for [reason] (the resolution it was
     * verified against could not be made, say).
     */
    data class NotCached(
        override val key: String,
        val source: SourceId,
        val reason: String,
    ) : PipelineEvent

    /** A cache read or write failed; the run goes on without it. [reason] is the exception's type. */
    data class CacheFailed(
        override val key: String,
        val operation: String,
        val reason: String,
    ) : PipelineEvent

    /** Every source has landed: [lines] cited lines from [sources] sources, fingerprinted. */
    data class EvidenceAssembled(
        override val key: String,
        val lines: Int,
        val sources: Int,
        val fingerprint: String,
    ) : PipelineEvent

    /** The insight was served from the synthesis cache: no call. */
    data class InsightCached(
        override val key: String,
    ) : PipelineEvent

    /** No insight is written for this track without a tap, for [reason]. */
    data class InsightWaits(
        override val key: String,
        val reason: String,
    ) : PipelineEvent

    /** A paid call started. */
    data class ModelCall(
        override val key: String,
        val provider: ProviderId,
        val model: String,
    ) : PipelineEvent

    /** The call finished: tokens billed, why it stopped, how long it took. */
    data class ModelDone(
        override val key: String,
        val usage: Usage,
        val stopReason: StopReason,
        val latency: Duration,
    ) : PipelineEvent

    data class ModelFailed(
        override val key: String,
        val kind: AiErrorKind,
    ) : PipelineEvent

    /** The grounding check's counts for the written insight. */
    data class Grounded(
        override val key: String,
        val counts: GroundingCounts,
    ) : PipelineEvent

    /** The model replied `NO_EVIDENCE`, or there was no evidence to write from. */
    data class NoEvidence(
        override val key: String,
    ) : PipelineEvent
}
