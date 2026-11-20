package app.sleevesight.app.settings

import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * *Reset to the defaults* (#960), under *Save and reset*: every setting back to what a new install
 * starts with ([reset]), but those [KEPT]. Built from the defaults, not from a list of the settings
 * reset, so a setting added later goes back to its default with no change here; one that should
 * stay joins [KEPT]. The API keys are not settings ([app.sleevesight.app.secrets.SecretStore]) and
 * the history is not either: a reset touches neither.
 */
object SettingsReset {
    /** Whether *Keep history for* going from [from] to [to] would delete history: null is *Forever*. */
    internal fun shortens(
        from: Duration?,
        to: Duration?,
    ): Boolean = to != null && (from == null || to < from)

    /**
     * A setting a reset keeps: [name] is its property in [Settings]; [carry] copies it from the
     * settings before the reset ([from]) into those after it ([into]), or leaves [into] as it is
     * when it keeps it only at times (*Keep history for*).
     */
    class Kept(
        val name: String,
        val carry: (from: Settings, into: Settings) -> Settings,
    )

    /**
     * What a reset keeps, the one list of it: the players followed (*Players*' switches), which AI
     * service is switched on, *Developer mode* and what is dismissed (*Restore dismissed messages*
     * brings that back); and *Keep history for* when its default is shorter, since a shorter time
     * deletes history, which a reset never does. The view (#1447) joins here when it comes.
     */
    val KEPT: List<Kept> =
        listOf(
            Kept("followedPlayers") { from, into -> into.copy(followedPlayers = from.followedPlayers) },
            Kept("aiProvider") { from, into -> into.copy(aiProvider = from.aiProvider) },
            Kept("developerMode") { from, into -> into.copy(developerMode = from.developerMode) },
            Kept("dismissed") { from, into -> into.copy(dismissed = from.dismissed) },
            // Kept only where its default is shorter: a reset deletes no history.
            Kept("historyLifetime") { from, into ->
                if (shortens(from.historyLifetime, into.historyLifetime)) {
                    into.copy(historyLifetime = from.historyLifetime)
                } else {
                    into
                }
            },
        )

    /** [current] reset to [defaults], all but what [KEPT] keeps. */
    fun reset(
        current: Settings,
        defaults: Settings,
    ): Settings = KEPT.fold(defaults) { into, kept -> kept.carry(current, into) }
}

/** [current] as *Reset to the defaults* would leave it (#960): what its dialog lists. */
fun SettingsStore.resetOf(current: Settings): Settings = SettingsReset.reset(current, defaults)

/**
 * *Reset to the defaults* (#960) on this store: each call saves a reset in [saves], which outlives
 * the screen, in line with the other saves ([saving]), its result whether it was saved, and notes it
 * in [SettingsStore.resets], so the screen showing then, a new one after a recreation (a reset that
 * changes the theme restarts the activity) included, says once that it is done. *Keep history for*
 * as last asked for at the call is dropped once the reset is saved, unless chosen again since
 * ([HistoryLifetimeAsked.mark]). A reset that changes *Reactive*'s mode tells [chosen], as the mode's
 * own choice does ([SettingsStore.writingModeChooser]): the pipeline, which follows the store's mode only until
 * the first choice in the process, then writes by the reset's mode.
 */
fun SettingsStore.resetter(
    saves: CoroutineScope,
    chosen: (WriteAutomatically) -> (WriteAutomatically?) -> Unit = { {} },
): () -> Deferred<Boolean> =
    {
        val mark = historyLifetimeAsked.mark()
        saving(saves) { resetToDefaults(mark, chosen) }.also { resets.saving(it) }
    }

/**
 * Every setting back to this store's defaults, but those [SettingsReset.KEPT] keeps (#960). Takes no
 * turn, as [SettingsStore.update]: [resetter] takes it. A mode it changes is told to [chosen] before
 * the save, and the function that returns is told what the store holds once it has ended, saved or
 * not.
 */
internal suspend fun SettingsStore.resetToDefaults(
    mark: HistoryLifetimeAsked.Mark = historyLifetimeAsked.mark(),
    chosen: (WriteAutomatically) -> (WriteAutomatically?) -> Unit = { {} },
) {
    val before = storedMode()
    val after = before?.let { resetOf(defaults.copy(writeAutomatically = it)).writeAutomatically }
    val settled = if (after != null && after != before) chosen(after) else null
    try {
        update { resetOf(it) }
        historyLifetimeAsked.forget(mark)
    } finally {
        settled?.invoke(storedMode())
    }
}

/**
 * The last *Reset to the defaults* asked for (#960), kept with the store, so its message is shown
 * once, by whichever screen is showing when it is saved ([take]).
 */
class Resets {
    private val last = MutableStateFlow<Pending?>(null)

    /** A reset, [saved] its result, not yet said to be done while [told] is false. */
    class Pending(
        val saved: Deferred<Boolean>,
    ) {
        internal val told = AtomicBoolean(false)
    }

    /** The last reset asked for, and each one after it. */
    val pending: StateFlow<Pending?> = last

    internal fun saving(saved: Deferred<Boolean>) {
        last.value = Pending(saved)
    }

    /** Whether [pending], once saved, is to be said done now: true once only, for one screen. */
    fun take(pending: Pending): Boolean = pending.told.compareAndSet(false, true)
}
