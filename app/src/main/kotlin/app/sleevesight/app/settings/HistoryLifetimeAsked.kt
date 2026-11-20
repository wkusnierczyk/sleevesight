package app.sleevesight.app.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * *Keep history for* as last asked for (#1126), while its save may still wait its turn in [store]'s
 * line ([SaveOrder]): kept with the store, not by the screen, so it outlives the row, the screen and
 * the activity, as the save does. The settings screen is its only writer. Dropped when that save
 * fails, unless another was asked for since.
 */
class HistoryLifetimeAsked internal constructor(
    private val store: SettingsStore,
) {
    private val asked = AtomicReference<Asked?>(null)

    private class Asked(
        val lifetime: Duration?,
    )

    /**
     * The value last asked for, saved yet or not, else [stored]: what a shorter time is judged
     * against, so a quick second choice is not compared with a value a queued save is about to
     * replace.
     */
    fun held(stored: Duration?): Duration? {
        val last = asked.get()
        return if (last != null) last.lifetime else stored
    }

    /** The value last asked for as it is now, for [forget]. */
    class Mark internal constructor(
        internal val at: Any?,
    )

    /** What is asked for now, taken when a reset (#960) is asked for. */
    fun mark(): Mark = Mark(asked.get())

    /**
     * Drops the value last asked for, once a reset (#960) has stored another, so [held] is the stored
     * one; unless another was asked for since [mark] was taken: that one's save comes after the reset's.
     */
    internal fun forget(mark: Mark) {
        @Suppress("UNCHECKED_CAST")
        (asked as AtomicReference<Any?>).compareAndSet(mark.at, null)
    }

    /**
     * Saves [lifetime] with [save] (the screen's, in line, [saving]), noted as asked for first; its
     * result whether it was saved, awaited in [saves], which outlives the screen.
     */
    fun save(
        lifetime: Duration?,
        save: (suspend () -> Unit) -> Deferred<Boolean>,
        saves: CoroutineScope,
    ): Deferred<Boolean> {
        val mine = Asked(lifetime)
        asked.set(mine)
        val saved = save { store.update { it.copy(historyLifetime = lifetime) } }
        saves.launch { if (!saved.await()) asked.compareAndSet(mine, null) }
        return saved
    }
}
