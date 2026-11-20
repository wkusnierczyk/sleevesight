package app.sleevesight.app.settings

import app.sleevesight.core.settings.Settings
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
 * *Reset to the defaults* (#960): every setting back to this store's defaults, but those
 * [SettingsReset.KEPT] keeps. Takes no turn, as [SettingsStore.update]: the settings screen's save
 * takes it. *Keep history for* as last asked for is dropped once the reset is saved, so the next
 * choice is judged against the value stored.
 */
suspend fun SettingsStore.resetToDefaults() {
    update { resetOf(it) }
    historyLifetimeAsked.forget()
}
