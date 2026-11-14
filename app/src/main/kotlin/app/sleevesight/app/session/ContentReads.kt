package app.sleevesight.app.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The threads `ArtLoader` reads `content://` art on, and the bounds on them. A provider is another
 * app's code, and one that ignores the `CancellationSignal` keeps its read's thread until it
 * returns, so:
 * - at most [cap] reads are in flight, a read counting until its thread is free again; when all are
 *   taken, [tryStart] refuses rather than queue, and the loader tries the next art source;
 * - at most [cap] - 1 of them (at least one) read from one authority, so a provider whose reads
 *   never return, cancelled or timed out, always leaves a slot for the other players' art;
 * - an authority with a read that ran past the timeout and has not returned yet is not asked again
 *   ([isStuck]) until that read returns, so such a provider holds one thread rather than its share.
 *
 * [executor] runs the reads; by default a pool of at most [cap] threads, each kept a minute once
 * idle. Its queue holds a read only in the moment between a slot's release and its thread going
 * idle, since no more than [cap] reads are ever started.
 */
class ContentReads(
    private val cap: Int = MAX_IN_FLIGHT,
    val executor: Executor = pool(cap),
) {
    private val slots = Semaphore(cap)
    private val perAuthority = maxOf(1, cap - 1)
    private val inFlight = ConcurrentHashMap<String, Int>()
    private val stuck = ConcurrentHashMap<String, Int>()

    /** True and a slot taken when a read of [authority] may start; release it with [finish]. */
    fun tryStart(authority: String): Boolean {
        if (isStuck(authority) || !slots.tryAcquire()) return false
        val admitted = admit(authority)
        // Checked again once admitted: another read of [authority] may have been marked meanwhile.
        val started = admitted && !isStuck(authority)
        when {
            !admitted -> slots.release()
            !started -> finish(authority)
        }
        return started
    }

    /** Frees the slot [tryStart] took, once the read's thread is done with the provider. */
    fun finish(authority: String) {
        inFlight.computeIfPresent(authority) { _, n -> (n - 1).takeIf { it > 0 } }
        slots.release()
    }

    /** Counts a read of [authority] in, unless it already has [perAuthority] in flight. */
    private fun admit(authority: String): Boolean {
        var admitted = false
        inFlight.compute(authority) { _, n ->
            val count = n ?: 0
            if (count < perAuthority) {
                admitted = true
                count + 1
            } else {
                n
            }
        }
        return admitted
    }

    /** A read of [authority] ran past the timeout and still holds its thread. */
    fun markStuck(authority: String) {
        stuck.merge(authority, 1, Int::plus)
    }

    /** Undoes one [markStuck] of [authority]: that read has returned. */
    fun unmarkStuck(authority: String) {
        stuck.computeIfPresent(authority) { _, n -> (n - 1).takeIf { it > 0 } }
    }

    fun isStuck(authority: String): Boolean = stuck.containsKey(authority)

    companion object {
        /**
         * Usually one read runs at a time (a track's art; the previous track's read is cancelled and
         * freed with it). Three leaves room for track changes while a read or two is held by a
         * provider that does not answer, and keeps the threads such providers can hold small.
         */
        const val MAX_IN_FLIGHT = 3

        /** The one set of reads for the process: the threads are the process's, not a loader's. */
        val SHARED = ContentReads()

        private const val IDLE_SECONDS = 60L

        private fun pool(cap: Int): Executor =
            ThreadPoolExecutor(cap, cap, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
                Thread(task, "art-read").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
    }
}
