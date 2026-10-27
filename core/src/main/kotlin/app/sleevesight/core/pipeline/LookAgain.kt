package app.sleevesight.core.pipeline

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Resolution

/**
 * A run that looks again (*Look again*, #617): every source switched on is looked up afresh, past
 * the caches ([app.sleevesight.core.sources.FreshLookups]), and what it finds is kept as a usual
 * lookup's is. [before] is what the run before it gathered: a source the fresh lookup cannot reach
 * (unreachable, or its adapter failed) keeps its state from [before], and an unreachable resolution
 * is [before]'s, so a network failure does not pass for new evidence, and a phone offline finds
 * nothing new rather than less.
 */
class LookAgain(
    val before: Gathered,
) {
    /**
     * [source]'s state from the run before, when the fresh lookup could not reach it ([landed]
     * unreachable, or its adapter failed) and that run found it; else null.
     */
    internal fun kept(
        source: SourceId,
        landed: SourceState,
    ): SourceState? {
        val missed =
            landed is SourceState.Failed || (landed as? SourceState.Done)?.result?.outcome == Outcome.Unreachable
        return before.sources[source]?.takeIf { missed && it is SourceState.Done && TrackRun.keeps(it) }
    }

    /** The resolution made before, when [fresh] could not be made and the run before made one; else null. */
    internal fun resolution(fresh: Resolution): Resolution? =
        before.resolution?.takeIf { fresh.outcome == Outcome.Unreachable && it.outcome != Outcome.Unreachable }
}
