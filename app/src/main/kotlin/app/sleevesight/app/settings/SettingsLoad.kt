package app.sleevesight.app.settings

import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/**
 * *Load from a file* (#815) on this store: each call, given the settings shown when *Load* was tapped,
 * the file's [SettingsFile.Contents] and the message to say once it is saved, saves the load in
 * [saves], which outlives the screen, in line with the other saves ([saving]); its result whether it
 * was saved. The load is made on the settings stored when its turn comes, so a setting the file does
 * not have keeps the value it has then; the developer settings only while *Developer mode* is on, and
 * *Keep history for* never shorter ([SettingsFile.load]). The message is said as a reset's is
 * ([SettingsStore.resets]), so a load that changes the theme, which restarts the activity, still
 * says it.
 *
 * A load that changes *Reactive*'s mode from the one shown is a choice of the mode, told to [chosen]
 * at the call, as a reset's is ([resetter]), so the pipeline then writes by it.
 */
fun SettingsStore.loader(
    saves: CoroutineScope,
    chosen: (WriteAutomatically) -> (WriteAutomatically?) -> Unit = { {} },
): (shown: Settings, contents: SettingsFile.Contents, message: String) -> Deferred<Boolean> =
    { shown, contents, message ->
        val mark = historyLifetimeAsked.mark()
        val mode = SettingsFile.load(contents, shown, shown.developerMode).settings.writeAutomatically
        val settled = if (mode != shown.writeAutomatically) chosen(mode) else null
        saving(saves) { loadFile(contents, mark, settled) }.also { resets.saving(it, message) }
    }

/**
 * What [contents] holds, loaded into the stored settings (#815). Takes no turn, as
 * [SettingsStore.update]: [loader] takes it. It tells [settled], if any, what the store holds for
 * *Reactive* once the save has ended, saved or not.
 */
internal suspend fun SettingsStore.loadFile(
    contents: SettingsFile.Contents,
    mark: HistoryLifetimeAsked.Mark = historyLifetimeAsked.mark(),
    settled: ((WriteAutomatically?) -> Unit)? = null,
) {
    try {
        var changedHistory = false
        update { stored ->
            SettingsFile.load(contents, stored, stored.developerMode).settings.also {
                changedHistory = it.historyLifetime != stored.historyLifetime
            }
        }
        // As after a reset: *Keep history for* is judged against what the load stored.
        if (changedHistory) historyLifetimeAsked.forget(mark)
    } finally {
        settled?.invoke(storedMode())
    }
}
