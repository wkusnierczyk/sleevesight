package app.sleevesight.app.settings

import app.sleevesight.app.settings.SettingsStore.Companion.saving
import app.sleevesight.core.settings.Dismissable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/**
 * A *Dismiss*, which puts an item away for good ([dismiss]), and the *Undo* in the message after it
 * ([undo]), which brings it back (#759); each result says whether it was saved
 * ([dismissals]).
 */
class Dismissals(
    val dismiss: (Dismissable) -> Deferred<Boolean>,
    val undo: (Dismissable) -> Deferred<Boolean>,
) {
    companion object {
        /** Saves nothing, and says it saved: for previews and tests. */
        val NONE: Dismissals = Dismissals({ CompletableDeferred(true) }, { CompletableDeferred(true) })
    }
}

/**
 * A *Dismiss* and its *Undo* (#759) from a screen, on this store: each saved in [saves], which
 * outlives the screen, so leaving it does not cut the save short, the result saying whether it was
 * saved. A save that fails leaves what was dismissed as it was.
 */
fun SettingsStore.dismissals(saves: CoroutineScope): Dismissals =
    Dismissals(
        dismiss = { item -> saves.saving { dismiss(item) } },
        undo = { item -> saves.saving { undismiss(item) } },
    )
