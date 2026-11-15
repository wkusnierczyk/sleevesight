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
 *
 * Every writer of a choice goes through it, so none overtakes a save still waiting its turn: the
 * settings screen's saves ([saving]), Home's (*Reactive*'s mode, the theme, the report button's
 * *Hide*, *Dismiss* and its *Undo*), the key dialogs' choices
 * ([app.sleevesight.app.ui.settings.applyChoices]), and *Players*' list as the app keeps it
 * ([SettingsStore.playerSeen], [SettingsStore.forgetPlayer]), and the services a key's first save
 * or *Purge keys* writes off ([app.sleevesight.app.secrets.servicesOff]): a choice to switch one on,
 * waiting its turn, cannot switch it back on after a purge. [SettingsStore.update] takes no turn of
 * its own; its callers take one. The turn is not reentrant, so nothing in a turn waits for another.
 *
 * **One lock order:** the key store's lock, then a turn. `servicesOff` takes its turn inside the key
 * store's lock ([app.sleevesight.app.secrets.SecretStore.set] and `reset`); nothing done in a turn
 * takes the key store's lock or waits on anything that does: a turn writes the settings through
 * [SettingsStore.update] with a transform that only computes, reads them back
 * ([SettingsStore.storedMode]), and tells the pipeline of *Reactive*'s mode without waiting
 * (`trySend`); a save's result is completed once its turn has ended. Keep it so: a turn that took
 * the key store's lock could wait forever on a key save waiting for that turn.
 *
 * The keys' gate ([app.sleevesight.app.secrets.KeyGate]) writes outside the line: it keeps a rule
 * applied again at every change of the settings or the keys, so a choice that lands after it is put
 * right at once.
 */
internal class SaveOrder {
    private val turn = Mutex()

    /** Starts [block] in [scope], in line; its result, or its failure, is the [Deferred]'s. */
    fun <T> async(
        scope: CoroutineScope,
        block: suspend () -> T,
    ): Deferred<T> = scope.async(start = CoroutineStart.UNDISPATCHED) { turn.withLock { block() } }

    /** Runs [block] in line, in the caller's coroutine: its place is taken when this is called. */
    suspend fun <T> inTurn(block: suspend () -> T): T = turn.withLock { block() }

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
