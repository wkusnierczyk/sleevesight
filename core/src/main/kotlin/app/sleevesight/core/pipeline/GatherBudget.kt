package app.sleevesight.core.pipeline

import app.sleevesight.core.sources.BusyWaits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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
 *
 * The budget grows by the time the run's requests spent waiting out a busy MusicBrainz ([busy],
 * *Wait when busy*, #1120, at most its total), and runs out only while none is waiting: that time is
 * the user's to give MusicBrainz. A source that stalls while MusicBrainz answers at once is cut at
 * the deadline as before.
 */
internal class GatherBudget(
    private val timeSource: TimeSource,
    private var left: Duration = Pipeline.GATHER_DEADLINE,
    private val busy: BusyWaits? = null,
) {
    // How much of [busy]'s waiting the budget has been given so far.
    private var credited = Duration.ZERO

    /**
     * Runs [block] within what is left of the budget, which then has the time it ran taken from it:
     * null when it ended in time, else how long it ran before the budget cut it, its coroutines
     * cancelled. With nothing left, it is cut before it starts.
     */
    suspend fun within(block: suspend CoroutineScope.() -> Unit): Duration? {
        val started = timeSource.markNow()
        var allowed = left + credit()
        if (!allowed.isPositive() && !waiting()) return Duration.ZERO
        val ended =
            coroutineScope {
                val work = async { block() }
                var outcome: Boolean? = null
                while (outcome == null) {
                    allowed += credit()
                    val remaining = allowed - started.elapsedNow()
                    outcome =
                        when {
                            // While MusicBrainz is waited out, the deadline moves on with the time: it is
                            // looked at again once no request waits.
                            busy != null && busy.waitingNow -> doneOrIdle(work, busy).takeIf { it }
                            remaining.isPositive() -> withTimeoutOrNull(remaining) { work.await() }?.let { true }
                            else -> false.also { work.cancel() }
                        }
                }
                outcome
            }
        val ran = started.elapsedNow()
        left = (allowed - ran).coerceAtLeast(Duration.ZERO)
        return ran.takeIf { !ended }
    }

    private fun waiting(): Boolean = busy?.waitingNow == true

    /** What [busy] has waited since the budget was last given it, at most its total in all. */
    private fun credit(): Duration {
        val waited = busy?.let { it.waited.coerceAtMost(it.total) } ?: return Duration.ZERO
        return (waited - credited).also { credited = waited }
    }

    /** True once [work] has ended, false once no request waits on [busy], whichever comes first. */
    private suspend fun doneOrIdle(
        work: Deferred<Unit>,
        busy: BusyWaits,
    ): Boolean =
        coroutineScope {
            val idle = async { busy.idle() }
            select {
                work.onJoin { true }
                idle.onAwait { false }
            }.also { idle.cancel() }
        }
}
