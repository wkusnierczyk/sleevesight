package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState

// What the home screen shows, worked out from what the session layer and the pipeline publish, as
// plain functions so the tests need no screen. Nothing here decides anything for the pipeline.

/**
 * The pipeline's state for the track [playing], or null when it has none yet: [view]'s track is
 * shown only while it is the track the session layer shows ([TrackIdentity.playsOn], the rule the
 * pipeline's insight track follows). A new track is shown at once, before its debounce, with its
 * evidence still to come, never with the last track's.
 */
internal fun trackFor(
    view: PipelineView,
    playing: NowPlaying,
): TrackState? = view.track?.takeIf { TrackIdentity.playsOn(it.nowPlaying, playing) }

/**
 * The year the recording was first released, as MusicBrainz's resolution gives it (`plan.md`,
 * *Release-date semantics*: the first release as "Released", shown since #438 as the year after
 * the album; the release being played is the `P` line). From MusicBrainz's result, which keeps it
 * in the retrieval cache, so a track served from the cache (which makes no resolution) shows the
 * same year; else from the run's resolution.
 * Null when MusicBrainz found none.
 */
internal fun releaseYear(track: TrackState?): String? {
    val kept = (track?.sources?.get(SourceId.MUSICBRAINZ) as? SourceState.Done)?.result?.firstReleased
    return (kept ?: track?.resolution?.firstReleaseDate)
        ?.take(YEAR_LENGTH)
        ?.takeIf { it.length == YEAR_LENGTH && it.all(Char::isDigit) }
}

/**
 * What the insight card needs besides the insight: the [track] it is of, and from the pipeline's
 * view and the settings, whether an insight can be written, what the pipeline offers beside it
 * ([offers]), and why a rewrite failed or that it was stopped ([rewriteFailed]).
 */
internal class InsightContext(
    val track: TrackState?,
    val aiReady: Boolean,
    val insightsOn: Boolean,
    val modelAllowed: Boolean,
    val offers: InsightOffers,
    val rewriteFailed: String?,
)

/**
 * What the pipeline offers beside the insight shown: Rewrite beside the card paid for ([rewrite]),
 * Write again after a call that failed or was stopped ([retry]), and Stop while the call is under
 * way ([stop]); and whether the Write offered is offered only because automatic writing paused
 * while the app was not on screen ([paused], #203).
 */
internal class InsightOffers(
    val rewrite: Boolean = false,
    val retry: Boolean = false,
    val stop: Boolean = false,
    val paused: Boolean = false,
)

/**
 * Where the insight card's taps go: Write and Rewrite ([onWrite]) and Stop ([onStop]) to the
 * pipeline, which says whether a tap does anything; a citation chip ([onCite]) to its line;
 * *Report a problem* ([onReport], #390) with the written insight and the track it was written for.
 */
internal class InsightActions(
    val onWrite: () -> Unit,
    val onStop: () -> Unit,
    val onCite: (String) -> Unit,
    val onReport: (TrackState, InsightState.Written) -> Unit,
)

/** A run of a sentence's text, or a citation in it. */
internal sealed interface Segment {
    data class Text(
        val text: String,
    ) : Segment

    data class Cite(
        val id: String,
    ) : Segment
}

/**
 * [sentence] split into its text and its citations (`[W1]`, `[M]`), as the grounding check reads
 * them. The citations become chips the app builds; the rest is plain text.
 */
internal fun segments(sentence: String): List<Segment> {
    val out = mutableListOf<Segment>()
    var at = 0
    for (match in CITATION.findAll(sentence)) {
        if (match.range.first > at) out += Segment.Text(sentence.substring(at, match.range.first))
        out += Segment.Cite(match.groupValues[1])
        at = match.range.last + 1
    }
    if (at < sentence.length) out += Segment.Text(sentence.substring(at))
    return out
}

/** How a citation chip shows: a line it can scroll to, the model's own knowledge, or a line that does not exist. */
internal enum class CiteKind { LINE, MODEL, UNKNOWN }

/**
 * What citing [id] points at, in [evidence]: `M` only when the model's own knowledge is allowed. A
 * line outside [held], the ids the prompt held when it left some of the track's out (#239), is no
 * line, as the grounding check reads it: the model was not given it, though the track has it.
 */
internal fun citeKind(
    id: String,
    evidence: EvidenceSet?,
    modelAllowed: Boolean,
    held: Set<String>? = null,
): CiteKind =
    when {
        id == SourceId.MODEL.tag -> if (modelAllowed) CiteKind.MODEL else CiteKind.UNKNOWN
        held != null && id !in held -> CiteKind.UNKNOWN
        evidence?.get(id) != null -> CiteKind.LINE
        else -> CiteKind.UNKNOWN
    }

/** A source's status chip: its [status], and the evidence sources whose lines it shows or hides. */
internal data class SourceChip(
    val source: SourceId,
    val status: ChipStatus,
    val filters: Set<SourceId>,
)

/** What a source's chip says. */
internal sealed interface ChipStatus {
    data object Release : ChipStatus

    data object Pending : ChipStatus

    data object Failed : ChipStatus

    data object NeedsKey : ChipStatus

    data object ModelAllowed : ChipStatus

    data class Done(
        val outcome: Outcome,
        val cached: Boolean,
    ) : ChipStatus
}

/**
 * The chips of the *Sources* section, in the evidence's order (`P` first, as the simulator shows
 * it): the player's release when there is a `P` line, each source looked up, each keyed source
 * that needs its key, and the model's own knowledge when it is allowed. The Wikipedia chip shows
 * and hides Wikidata's lines too, which its adapter brings.
 */
internal fun sourceChips(
    track: TrackState,
    needsKey: Set<SourceId>,
    modelAllowed: Boolean,
): List<SourceChip> {
    val chips = mutableListOf<SourceChip>()
    if (track.evidence
            ?.lines
            .orEmpty()
            .any { it.source == SourceId.PLAYER }
    ) {
        chips += SourceChip(SourceId.PLAYER, ChipStatus.Release, setOf(SourceId.PLAYER))
    }
    val order = EvidenceSet.SOURCE_ORDER
    val looked =
        track.sources.entries.sortedBy { (source, _) ->
            order.indexOf(source).let {
                if (it <
                    0
                ) {
                    order.size
                } else {
                    it
                }
            }
        }
    for ((source, state) in looked) {
        val status =
            when (state) {
                SourceState.Pending -> ChipStatus.Pending
                is SourceState.Failed -> ChipStatus.Failed
                is SourceState.Done -> ChipStatus.Done(state.result.outcome, state.cached)
            }
        chips += SourceChip(source, status, filtersOf(source))
    }
    for (source in order.filter { it in needsKey && it !in track.sources }) {
        chips += SourceChip(source, ChipStatus.NeedsKey, filtersOf(source))
    }
    if (modelAllowed) chips += SourceChip(SourceId.MODEL, ChipStatus.ModelAllowed, setOf(SourceId.MODEL))
    return chips
}

private fun filtersOf(source: SourceId): Set<SourceId> =
    if (source == SourceId.WIKIPEDIA) setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA) else setOf(source)

/**
 * Of [chips], those *Sources* shows by name (#440): a source that gave lines to [evidence], whatever
 * else it found, and never the model's own knowledge, which gives none. The rest, and every chip's
 * status, show only with *Show grounding details* on.
 */
internal fun chipsWithLines(
    chips: List<SourceChip>,
    evidence: EvidenceSet?,
): List<SourceChip> {
    val sources = evidence?.lines.orEmpty().mapTo(mutableSetOf()) { it.source }
    return chips.filter { chip -> chip.source != SourceId.MODEL && chip.filters.any { it in sources } }
}

/** What *Sources* tells the listener of a source (#440): only what needs them or went wrong. */
internal sealed interface SourceNote {
    val source: SourceId

    /** On, but its key is not saved: it was not asked. */
    data class NeedsKey(
        override val source: SourceId,
    ) : SourceNote

    /** It could not be reached. */
    data class Unreachable(
        override val source: SourceId,
    ) : SourceNote

    /** Its lookup went wrong. */
    data class Failed(
        override val source: SourceId,
    ) : SourceNote
}

/**
 * The notes of [chips], in their order (#440): a source that needs its key, one that could not be
 * reached, and one whose lookup failed; nothing for one that found nothing, was rejected or is off.
 * While the *Sources* section says the phone is [offline], which covers it, an unreachable source
 * has no note of its own.
 */
internal fun sourceNotes(
    chips: List<SourceChip>,
    offline: Boolean,
): List<SourceNote> =
    chips.mapNotNull { chip ->
        val status = chip.status
        when {
            status == ChipStatus.NeedsKey -> SourceNote.NeedsKey(chip.source)
            status == ChipStatus.Failed -> SourceNote.Failed(chip.source)
            status is ChipStatus.Done && status.outcome == Outcome.Unreachable && !offline ->
                SourceNote.Unreachable(chip.source)
            else -> null
        }
    }

/** A candidate a source rejected: the [source], what it was looked up for ([subject], when named) and why. */
internal data class RejectedMatch(
    val source: SourceId,
    val subject: String?,
    val reason: String,
)

/**
 * Each match the sources rejected, with its reason: every candidate a source lists
 * (`SourceResult.rejections`), and a source's own rejection when it lists none.
 */
internal fun rejectedMatches(track: TrackState): List<RejectedMatch> =
    landed(track).flatMap { (source, done) ->
        val listed = done.result.rejections.map { RejectedMatch(source, it.subject, it.reason) }
        val own = (done.result.outcome as? Outcome.Rejected)?.let { RejectedMatch(source, null, it.reason) }
        listed.ifEmpty { listOfNotNull(own) }
    }

/** The lines of matches that could not be verified: shown, never cited (D4). */
internal fun unverifiedLines(track: TrackState): List<EvidenceDraft> =
    landed(track).flatMap { (_, done) -> done.result.unverified }

private fun landed(track: TrackState): List<Pair<SourceId, SourceState.Done>> {
    val order = EvidenceSet.SOURCE_ORDER
    return track.sources.entries
        .mapNotNull { (source, state) -> (state as? SourceState.Done)?.let { source to it } }
        .sortedBy { (source, _) -> order.indexOf(source) }
}

private const val YEAR_LENGTH = 4

// The grounding check's citation (`GroundingCheck`): one or two capitals and an optional number.
private val CITATION = Regex("\\[([A-Z]{1,2}[0-9]*)\\]")
