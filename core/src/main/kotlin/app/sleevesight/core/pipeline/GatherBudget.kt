package app.sleevesight.core.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A run's gather deadline (#983, [Pipeline.GATHER_DEADLINE]): one budget for its lookups, its sources'
 * with MusicBrainz's resolution and then those in the track's language (#902), counted on
 * [timeSource] only while they run, not while they wait for the [RunStep.LOOKUPS] grant. Once it is
 * spent, what is still under way is cancelled, and the run lands what is still pending as
 * unreachable, so a *Retry* (#724) or back online (#192) asks it again; each request keeps its own
 * timeouts and retries under it.
 */
internal class GatherBudget(
    private val timeSource: TimeSource,
    private var left: Duration = Pipeline.GATHER_DEADLINE,
) {
    /**
     * Runs [block] within what is left of the budget, which then has the time it ran taken from it:
     * null when it ended in time, else how long it ran before the budget cut it, its coroutines
     * cancelled. With nothing left, it is cut before it starts.
     */
    suspend fun within(block: suspend CoroutineScope.() -> Unit): Duration? {
        val started = timeSource.markNow()
        val ended =
            withTimeoutOrNull(left) {
                block()
                true
            }
        val ran = started.elapsedNow()
        left = (left - ran).coerceAtLeast(Duration.ZERO)
        return ran.takeIf { ended == null }
    }
}
