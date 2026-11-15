package app.sleevesight.app.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Saves made one after another, in the order they are asked for (#1146): each starts once every
 * save asked for before it has ended. Its place in line is taken in the call itself
 * ([CoroutineStart.UNDISPATCHED] up to the lock, which [Mutex] grants first come, first served),
 * not when the scope's dispatcher gets round to it, so a later save never goes first.
 */
internal class SaveOrder {
    private val turn = Mutex()

    /** Starts [block] in [scope], in line; its result, or its failure, is the [Deferred]'s. */
    fun <T> async(
        scope: CoroutineScope,
        block: suspend () -> T,
    ): Deferred<T> = scope.async(start = CoroutineStart.UNDISPATCHED) { turn.withLock { block() } }

    /** Launches [block] in [scope], in line; a failure reaches [scope], awaited or not. */
    fun launch(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { turn.withLock { block() } }
}

/**
 * Runs [save] in [saves], after every save on this store asked for before it (#1146), its result
 * whether it was saved: false after an I/O error, which leaves the settings as they were. Any other
 * failure fails the save, and reaches [saves], as the settings screen's saves do; the next save
 * still runs.
 */
internal fun SettingsStore.saving(
    saves: CoroutineScope,
    save: suspend () -> Unit,
): Deferred<Boolean> =
    saveOrder.async(saves) {
        try {
            save()
            true
        } catch (_: IOException) {
            false
        }
    }
