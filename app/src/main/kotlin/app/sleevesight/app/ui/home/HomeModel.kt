package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.RewriteOffer
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.citationId

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
 * What the insight card needs besides the insight: the [track] it is of, and from the pipeline's
 * view and the settings, whether an insight can be written, what the pipeline offers beside it
 * ([offers]); and the switched-on
 * AI service's model, while it is found unavailable ([unavailable], #540), which no insight is
 * written with until another is chosen. *Reactive*'s [mode], null before the settings' first read,
 * and whether automatic writing waits for the user ([waiting], `PipelineView.waiting`) say whether
 * the line about the split button says it writes automatically (#615). Whether the insight shown
 * is the card paid for, kept from before in place of an insight the run lacks ([cardShown],
 * `PipelineView.cardShown`), and why its rewrite failed or that it was stopped ([rewriteFailed],
 * `PipelineView.rewriteFailed`). The sources it draws on that did not answer ([unanswered], #1121),
 * said under a written insight's text with *Retry*.
 */
@Suppress("LongParameterList") // Each what one part of the card says.
internal class InsightContext(
    val track: TrackState?,
    val aiReady: Boolean,
    val insightsOn: Boolean,
    val modelAllowed: Boolean,
    val offers: InsightOffers,
    val unavailable: UnavailableModel? = null,
    val mode: WriteAutomatically? = null,
    val waiting: Boolean = false,
    val cardShown: Boolean = false,
    val rewriteFailed: String? = null,
    val unanswered: Unanswered? = null,
)

/**
 * What the pipeline offers beside the insight shown: Rewrite beside the card paid for ([rewrite],
 * once a settings change changed its key), Write again after a call that failed or was
 * stopped ([retry]), and Stop while the call is under
 * way ([stop]); and why automatic writing left the track the Write offered is for: it came to play
 * while automatic writing was paused while the app was not on screen ([paused], #203), or it
 * followed the last one by itself under *On track skip (auto)* ([followed], #625). *Look again*
 * (#617): offered beside the card paid for ([lookAgain]), looking the sources up ([lookingAgain]),
 * finding something new, with *Rewrite insight* offered for it ([somethingNew]), or nothing new
 * ([nothingNew]). What *Rewrite insight* would change, while offered ([rewriteWith], #854), which
 * the line offering it names.
 */
@Suppress("LongParameterList") // Each what one line or action of the card says.
internal class InsightOffers(
    val rewrite: Boolean = false,
    val retry: Boolean = false,
    val stop: Boolean = false,
    val paused: Boolean = false,
    val followed: Boolean = false,
    val lookAgain: Boolean = false,
    val lookingAgain: Boolean = false,
    val nothingNew: Boolean = false,
    val somethingNew: Boolean = false,
    val rewriteWith: RewriteOffer? = null,
)

/**
 * Where the insight card's taps go: Write ([onWrite]), Stop ([onStop]) and *Look again*
 * ([onLookAgain], #617) to the pipeline, which says whether a tap does anything; a citation chip
 * ([onCite]) to its line; the link of a model found unavailable to the settings ([onSettings],
 * #540); *Report a problem* ([onReport], #390) with the written insight and the track it was
 * written for.
 */
internal class InsightActions(
    val onWrite: () -> Unit,
    val onStop: () -> Unit,
    val onCite: (String) -> Unit,
    val onSettings: () -> Unit = {},
    val onLookAgain: () -> Unit = {},
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
 * [sentence] split into its text and its citations (`[W1]`, `[AI]`), as the grounding check reads
 * them, a legacy `[M]` as `AI` (#668) and a legacy `[G1]` as `S1` (#769) ([citationId]). The
 * citations become chips the app builds; the rest is plain text.
 */
internal fun segments(sentence: String): List<Segment> {
    val out = mutableListOf<Segment>()
    var at = 0
    for (match in CITATION.findAll(sentence)) {
        if (match.range.first > at) out += Segment.Text(sentence.substring(at, match.range.first))
        out += Segment.Cite(citationId(match.groupValues[1]))
        at = match.range.last + 1
    }
    if (at < sentence.length) out += Segment.Text(sentence.substring(at))
    return out
}

/** How a citation chip shows: a line it can scroll to, the model's own knowledge, or a line that does not exist. */
internal enum class CiteKind { LINE, MODEL, UNKNOWN }

/**
 * What citing [id] points at, in [evidence]: `AI` only when the model's own knowledge is allowed. A
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
        id == GroundingCheck.MODEL_ID -> if (modelAllowed) CiteKind.MODEL else CiteKind.UNKNOWN
        held != null && id !in held -> CiteKind.UNKNOWN
        evidence?.get(id) != null -> CiteKind.LINE
        else -> CiteKind.UNKNOWN
    }

/**
 * A source's status chip: its [status], and the evidence sources whose lines it shows or hides. With
 * a [language] (the language's code, #902), the source's lookup in the track's language
 * (`TrackState.inLanguage`): its row of *Sources*' table is its own, and what it found is from its
 * own lines, those in that language; its letter shows and hides its source's lines, as the source's
 * own row's does, since they are cited by the same letter.
 */
internal data class SourceChip(
    val source: SourceId,
    val status: ChipStatus,
    val filters: Set<SourceId>,
    val language: String? = null,
) {
    /**
     * The lines of [lines] that are this chip's own: its sources', found in its [language] (none for
     * English); a line marked with a language by its script (#949) is its English lookup's.
     */
    fun own(lines: List<EvidenceLine>): List<EvidenceLine> =
        lines.filter { it.source in filters && it.found == language }

    /** Its key among the table's rows, in its test tags: the source's tag, with the language after a colon. */
    val key: String get() = language?.let { "${source.tag}:$it" } ?: source.tag
}

/** What a source's chip says. */
internal sealed interface ChipStatus {
    data object Release : ChipStatus

    data object Pending : ChipStatus

    data object Failed : ChipStatus

    data object NeedsKey : ChipStatus

    data object ModelAllowed : ChipStatus

    /**
     * A lookup done, with its [outcome], [cached] when kept from before. [withoutMusicBrainz] for a
     * rejected match whose every rejection was only for want of what MusicBrainz resolves, while
     * MusicBrainz could not be reached (its row and the resolution), in a result not kept from before (#1069,
     * `SourceResult.withoutMusicBrainz`): its row says the match was not
     * confirmed without MusicBrainz, rather than that nothing was found.
     */
    data class Done(
        val outcome: Outcome,
        val cached: Boolean,
        val withoutMusicBrainz: Boolean = false,
    ) : ChipStatus
}

/**
 * The chips of the *Sources* section, in the evidence's order (`P` first, as the simulator shows
 * it): the player's release when there is a `P` line, each source looked up, each keyed source
 * that needs its key, each keyed source switched off ([off]) that was not looked up, as off (#667:
 * one with no key saved is off, not in need of its key), and the model's own knowledge when it is
 * allowed. The Wikipedia chip shows and hides Wikidata's lines too, which its adapter brings. Each
 * lookup in the track's language (#902, `TrackState.inLanguage`) has a chip of its own, after its
 * source's.
 */
internal fun sourceChips(
    track: TrackState,
    needsKey: Set<SourceId>,
    modelAllowed: Boolean,
    off: Set<SourceId> = emptySet(),
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
    val inLanguage = track.inLanguage
    val language = inLanguage?.sources.orEmpty()
    // MusicBrainz could not be reached for the lookups shown: its row says so, and so does the
    // resolution they were checked against. A retry of another source alone resolves again while
    // MusicBrainz's row stays as it was, so a resolution that answered means it was not why (#1069).
    val unreached =
        (track.sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result?.outcome == Outcome.Unreachable &&
            track.resolution?.outcome == Outcome.Unreachable
    for ((source, state) in looked) {
        chips += SourceChip(source, chipStatus(state, unreached), filtersOf(source))
        language[source]?.let {
            chips += SourceChip(source, chipStatus(it, unreached), filtersOf(source), inLanguage?.language)
        }
    }
    for (source in order.filter { it !in track.sources }) {
        val status =
            when (source) {
                in needsKey -> ChipStatus.NeedsKey
                in off -> ChipStatus.Done(Outcome.SourceOff, cached = false)
                else -> null
            }
        status?.let { chips += SourceChip(source, it, filtersOf(source)) }
    }
    if (modelAllowed) chips += SourceChip(SourceId.MODEL, ChipStatus.ModelAllowed, setOf(SourceId.MODEL))
    return chips
}

// [state]'s status; a rejection only for want of MusicBrainz marked so while it was [unreached] (#1069),
// only in a result made now: one made while MusicBrainz could not be reached is never kept, so a kept
// one was made while it answered, and the outage now is not why its match was rejected.
private fun chipStatus(
    state: SourceState,
    unreached: Boolean,
): ChipStatus =
    when (state) {
        SourceState.Pending -> ChipStatus.Pending
        is SourceState.Failed -> ChipStatus.Failed
        is SourceState.Done ->
            ChipStatus.Done(
                state.result.outcome,
                state.cached,
                unreached && !state.cached && state.result.withoutMusicBrainz,
            )
    }

private fun filtersOf(source: SourceId): Set<SourceId> =
    if (source == SourceId.WIKIPEDIA) setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA) else setOf(source)

/**
 * A candidate a source rejected: the [source], what it was looked up for ([subject], when named) and
 * why; in the track's [language] (its code, #902) for its lookup in that language.
 */
internal data class RejectedMatch(
    val source: SourceId,
    val subject: String?,
    val reason: String,
    val language: String? = null,
)

/**
 * Each match the sources rejected, with its reason: every candidate a source lists
 * (`SourceResult.rejections`), and a source's own rejection when it lists none; then those of the
 * lookups in the track's language (#902, `TrackState.inLanguage`), marked with it.
 */
internal fun rejectedMatches(track: TrackState): List<RejectedMatch> {
    val language = track.inLanguage
    val inLanguage =
        language
            ?.sources
            ?.entries
            ?.mapNotNull { (source, state) -> (state as? SourceState.Done)?.let { source to it } }
            .orEmpty()
    return landed(track).flatMap { (source, done) -> rejected(source, done, null) } +
        inLanguage.flatMap { (source, done) -> rejected(source, done, language?.language) }
}

// What [source]'s lookup [done] rejected, in [language] when it was made in one.
private fun rejected(
    source: SourceId,
    done: SourceState.Done,
    language: String?,
): List<RejectedMatch> {
    val listed = done.result.rejections.map { RejectedMatch(source, it.subject, it.reason, language) }
    val own = (done.result.outcome as? Outcome.Rejected)?.let { RejectedMatch(source, null, it.reason, language) }
    return listed.ifEmpty { listOfNotNull(own) }
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

// The grounding check's citation (`GroundingCheck`): one or two capitals and an optional number.
private val CITATION = Regex("\\[([A-Z]{1,2}[0-9]*)\\]")
