package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.GroundedText
import kotlin.time.Instant

/**
 * Everything the UI renders for one track (`design/architecture.md`, *State*), immutable: the
 * pipeline publishes a new one as each stage lands.
 *
 * @property sources one entry per adapter, by its source: [SourceState.Pending] until it lands.
 *   A source the user switched off is [SourceState.Done] with `Outcome.SourceOff`.
 * @property resolution what MusicBrainz resolved, once it has (null before, and when every source
 *   was served from the cache, so nothing needed it).
 * @property evidence the cited evidence, with ids and fingerprint, once every source has landed.
 * @property genres the genre labels of the track, its artists and its album, from the label
 *   sources switched on, a row per level ([app.sleevesight.core.genres.combineGenres], #439):
 *   Last.fm's artists' and MusicBrainz's recording's with the evidence, once every source has
 *   landed; MusicBrainz's artists' and Last.fm's track's and album's join later, as their requests
 *   answer, which the insight does not wait for (`design/architecture.md`, *Evidence*).
 * @property laterLabels how those later requests stand, by label source (#772): asking, done, or
 *   failed; a source not in it has none to make.
 * @property genresAssembled whether [genres] and [laterLabels] are in (#772): set with them, in one
 *   update, as the evidence is assembled. Until then, no genre may still mean genres to come, even
 *   once every source has landed ([gathered]), while the last one's result is being kept.
 */
data class TrackState(
    val nowPlaying: NowPlaying,
    val normalized: NormalizedTrack,
    val sources: Map<SourceId, SourceState>,
    val resolution: Resolution? = null,
    val evidence: EvidenceSet? = null,
    val genres: TrackGenres = TrackGenres(),
    val insight: InsightState = InsightState.Idle(canWrite = false),
    val laterLabels: Map<LabelSource, LaterLabels> = emptyMap(),
    val genresAssembled: Boolean = false,
) {
    /** Whether every source has landed. */
    val gathered: Boolean get() = sources.values.none { it is SourceState.Pending }
}

/**
 * How a label source's requests after the evidence stand (#772; [TrackState.laterLabels]): still
 * [ASKING] (or waiting to start), [DONE], or [FAILED] (a request could not reach the source, or
 * failed).
 */
enum class LaterLabels {
    ASKING,
    DONE,
    FAILED,
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

/**
 * What a run's sources ended with, once every one has landed ([RunControl.gathered]): each
 * source's state, and MusicBrainz's resolution when the run made one (null when nothing needed
 * it, or it was not made). A later run of the same track started with it ([Pipeline.run]'s
 * `again`, #192) asks again only the sources [asked] here, and keeps the others as they landed:
 * every source [unreachable], or with [only] (a *Retry*'s, #724, [asking]), those of them it
 * names, the others kept unreachable.
 */
data class Gathered(
    val sources: Map<SourceId, SourceState>,
    val resolution: Resolution?,
    val only: Set<SourceId>? = null,
) {
    /** The sources that ended `Unreachable`: a network or source error. */
    val unreachable: Set<SourceId>
        get() = sources.filterValues { (it as? SourceState.Done)?.result?.outcome == Outcome.Unreachable }.keys

    /** The sources a run started with it asks again: those [unreachable], of [only] when it names some. */
    val asked: Set<SourceId>
        get() = only?.let { unreachable intersect it } ?: unreachable

    /**
     * As a run asking again [sources] alone starts with it (a *Retry*, #724): the other sources
     * unreachable stay so, not asked.
     */
    fun asking(sources: Set<SourceId>): Gathered = copy(only = sources)
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

    /**
     * The model is writing; [partial] is the text so far, not yet checked. [held] are the ids of the
     * lines its prompt held when it left some of the track's out (#239, `PromptEvidence`), null when
     * it held them all: a citation outside them shows as one of no line, as the check will read it.
     */
    data class Writing(
        val partial: String,
        val held: Set<String>? = null,
    ) : InsightState

    /**
     * Written and checked. [cached] when served from the synthesis cache, with no call; [usage] is
     * the call's, null when cached. [complete] is false when the reply was cut off at the token
     * limit: shown, but not kept. [held] are the ids of the lines its prompt held when it left some
     * of the track's out (#239), as the check read it: a citation outside them is `unknown`, and its
     * chip shows and reads as one of no line, though the track has that line. Null when the prompt
     * held every line, and for a card from the cache, which is kept only when it cites none outside
     * them (`InsightWriter.finish`). [writtenBy] is the prompt version, AI service, model and
     * effort of the card's key, the call's or the cached card's, which a problem report names
     * (#390); null only where no pipeline wrote it (a test's fixture). [writtenAt] is when it was
     * written: the call's end, or as the cache says for a cached card; null where the cache does not
     * say. It is shown no longer than *Keep history for* after it (#643, `pastKeepHistory`), as
     * the cache serves it.
     */
    data class Written(
        val grounded: GroundedText,
        val cached: Boolean,
        val usage: Usage? = null,
        val complete: Boolean = true,
        val held: Set<String>? = null,
        val writtenBy: WrittenBy? = null,
        val writtenAt: Instant? = null,
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

/**
 * What a written insight was written with, from its card's key ([CardKey]): the insight prompt's
 * [promptVersion], the AI service ([provider]) and its [model], and the reasoning [effort] asked
 * for, null for the service's default.
 */
data class WrittenBy(
    val promptVersion: Int,
    val provider: ProviderId,
    val model: String,
    val effort: ReasoningEffort?,
)
