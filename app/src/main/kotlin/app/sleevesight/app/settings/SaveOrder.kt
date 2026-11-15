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
 * ([SettingsStore.playerSeen], [SettingsStore.forgetPlayer]). [SettingsStore.update] itself takes
 * no turn, as it runs inside one; the turn is not reentrant, so nothing in a turn waits for another.
 * The keys' gate ([app.sleevesight.app.secrets.KeyGate], `servicesOff`) writes outside it: it keeps
 * a rule rather than a choice, and applies it again at every change of the settings, so a save that
 * lands after it is put right at once; in line, a key's save would wait on the settings' saves
 * while it holds the key store's lock.
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
