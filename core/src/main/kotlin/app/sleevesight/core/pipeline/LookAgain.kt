package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.LineContent
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Resolution

/**
 * A run that looks again (*Look again*, #617): every source switched on is looked up afresh, past
 * the caches ([app.sleevesight.core.sources.FreshLookups]), and what it finds is kept in them as a
 * usual lookup's is. [before] is what the run before it gathered.
 *
 * The evidence is never made of less than [before]'s: a source keeps its state from [before] unless
 * the fresh lookup brought a line that says what none of [before]'s lines from it say
 * ([LineContent]). So a source that failed, failed in part (an artist's article that did not
 * answer), was left provisional, or was judged against a resolution that could not be made, keeps
 * what it found then, and so does one that found the same again. An unreachable resolution is
 * [before]'s when that run made one.
 */
class LookAgain(
    val before: Gathered,
) {
    /**
     * [source]'s state from the run before, when it found something ([TrackRun.keeps]) and the fresh
     * lookup ([landed]) brought nothing new from it; else null, and [landed] stands.
     */
    internal fun kept(
        source: SourceId,
        landed: SourceState,
    ): SourceState? {
        val earlier = before.sources[source] as? SourceState.Done
        if (earlier == null || !TrackRun.keeps(earlier)) return null
        val fresh = (landed as? SourceState.Done)?.result?.takeIf { it.outcome != Outcome.Unreachable }
        val brought =
            fresh != null &&
                LineContent.bringsNew(fresh.evidence.map(LineContent::of), earlier.result.evidence.map(LineContent::of))
        return earlier.takeUnless { brought }
    }

    /** The resolution made before, when [fresh] could not be made and the run before made one; else null. */
    internal fun resolution(fresh: Resolution): Resolution? =
        before.resolution?.takeIf { fresh.outcome == Outcome.Unreachable && it.outcome != Outcome.Unreachable }
}
