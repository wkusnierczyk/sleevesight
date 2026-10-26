package app.sleevesight.core.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The boundary *Clear history* draws (#547): work started before it writes nothing after it.
 *
 * Work is marked, when it starts, with the generation then ([mark], a coroutine context element its
 * children inherit): the app marks each run and preparation of a track. Each store that keeps what
 * such work finds (the caches, MusicBrainz's answers held in memory, the listening session's
 * facts) writes through [ifCurrent], which drops the write when the work's generation is no longer
 * the current one. [advance] ends a generation: it waits for the writes in progress, holding the
 * lock they hold, so a write either ends before it, and is deleted by the clear that follows, or
 * starts after it, and is dropped. Work with no mark of this boundary (the CLI's, the tests') is
 * always current, and takes no lock.
 */
class ClearEpoch {
    private val lock = Mutex()
    private val generation = MutableStateFlow(0L)

    /** The mark of work starting now, for its coroutine context. */
    fun mark(): CoroutineContext.Element = Started(this, generation.value)

    /** Ends the current generation, once the writes in progress have ended. */
    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
    suspend fun advance() {
        lock.withLock { generation.update { it + 1 } }
    }

    /**
     * Runs [write] unless the work [context] belongs to started before the last [advance], and
     * returns its result, or null for a write dropped. A marked write runs under the lock [advance]
     * waits for, so it must not wait for another write through this boundary.
     */
    suspend fun <T> ifCurrent(
        context: CoroutineContext,
        write: suspend () -> T,
    ): T? {
        val started = startedIn(context) ?: return write()
        return lock.withLock { if (started == generation.value) write() else null }
    }

    /** Whether the work [context] belongs to is of the current generation, or unmarked. */
    fun isCurrent(context: CoroutineContext): Boolean = startedIn(context)?.let { it == generation.value } ?: true

    // The generation the work [context] belongs to started in, or null for work this did not mark.
    private fun startedIn(context: CoroutineContext): Long? = context[Started]?.takeIf { it.epoch === this }?.generation

    private class Started(
        val epoch: ClearEpoch,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Started) {
        companion object Key : CoroutineContext.Key<Started>
    }
}
