package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.LineContent
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult

/**
 * A run that looks again (*Look again*, #617): every source switched on is looked up afresh, past
 * the caches ([app.sleevesight.core.sources.FreshLookups]). [before] is what the run before it
 * gathered. A look again writes nothing itself: what it finds becomes the run's evidence, which
 * *Rewrite insight* writes from on a tap.
 *
 * That evidence is never made of less than [before]'s. A source keeps its state from [before]
 * unless the fresh lookup brought a line that says what none of [before]'s lines from it say
 * ([LineContent]): so a source that failed, failed in part, was left provisional, or was judged
 * against a resolution that could not be made keeps what it found then, and so does one that found
 * the same again. A source that brought a new line but lacks a kind of line it had before keeps
 * [before]'s lines of that kind beside the fresh ones: an artist's lines by the page they cite, so
 * each credited artist's are its own (one artist's article that did not answer, beside a new line
 * about another), other lines by their scope. An unreachable resolution is [before]'s when that
 * run made one. The retrieval cache keeps a fresh result only as it came: one shown with [before]'s
 * state, or with lines carried from it, is not kept, so lines carried keep their own lifetime.
 */
class LookAgain(
    val before: Gathered,
) {
    /**
     * [source]'s state as the look again shows it, when it is not [landed] as it came: [before]'s,
     * when that run found something ([TrackRun.keeps]) and [landed] brought nothing new from it; or
     * [landed] with [before]'s lines of the scopes it lacks; else null, and [landed] stands.
     */
    internal fun kept(
        source: SourceId,
        landed: SourceState,
    ): SourceState? {
        val earlier = earlier(source) ?: return null
        val fresh = reached(landed)
        return when {
            fresh == null || !LineContent.bringsNew(contents(fresh), contents(earlier.result)) -> earlier
            else -> carried(fresh, earlier.result)?.let { SourceState.Done(it, cached = false) }
        }
    }

    /** The resolution made before, when [fresh] could not be made and the run before made one; else null. */
    internal fun resolution(fresh: Resolution): Resolution? =
        before.resolution?.takeIf { fresh.outcome == Outcome.Unreachable && it.outcome != Outcome.Unreachable }

    // [source]'s state in [before], when it found something.
    private fun earlier(source: SourceId): SourceState.Done? =
        (before.sources[source] as? SourceState.Done)?.takeIf { TrackRun.keeps(it) }

    // [landed]'s result, when the source answered.
    private fun reached(landed: SourceState): SourceResult? =
        (landed as? SourceState.Done)?.result?.takeIf { it.outcome != Outcome.Unreachable }

    private fun contents(result: SourceResult): List<LineContent> = result.evidence.map(LineContent::of)

    // [fresh] with [earlier]'s lines of the kinds it lacks, or null when it lacks none.
    private fun carried(
        fresh: SourceResult,
        earlier: SourceResult,
    ): SourceResult? {
        val kinds = fresh.evidence.map(::kind).toSet()
        val missing = earlier.evidence.filter { kind(it) !in kinds }
        return if (missing.isEmpty()) null else fresh.copy(evidence = fresh.evidence + missing)
    }

    // A kind of line, as carried: an artist's lines by their page (each artist's own), others by scope.
    private fun kind(line: EvidenceDraft): Pair<Scope, String?> =
        line.scope to line.url?.takeIf { line.scope == Scope.ARTIST }
}
