package app.sleevesight.app.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sleevesight.app.SleevesightApplication
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Drops a player from *Players* once Android says its app is gone (#535): `PACKAGE_FULLY_REMOVED`,
 * which a manifest receiver still gets from Android 8 on (it is on the implicit-broadcast
 * exemptions), sent after an uninstall and its data's removal, never for an update. Android sends
 * it only for a package this app can see (package visibility), so a player the app cannot see keeps
 * its entry, hidden. The process also forgets having seen the player (`AppGraph.forgetRemovedPlayer`),
 * so one reinstalled without a restart is listed again once it reports music (#579). The write runs
 * in the graph's saves scope, the broadcast held open until it ends; one that fails is logged, and
 * the entry stays.
 *
 * It hears the removal of any app visible through `<queries>`, not only of players, and when the
 * process is not running (notification access off, say), the broadcast starts it and builds the
 * whole graph, pipeline included, for what is usually a write that changes nothing. That is
 * accepted: it is rare, as the listener usually keeps the process alive; such a start does what any
 * start of the process does, and no more; and the settings' DataStore, one per file in a process,
 * belongs to the graph, so a lighter path would need the store taken out of it.
 */
class PlayerRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val graph = (context.applicationContext as SleevesightApplication).graph
        val pending = goAsync()
        graph.settingsSaves.launch {
            try {
                graph.forgetRemovedPlayer(packageName)
            } catch (_: IOException) {
                // No exception text: an I/O message may name a path.
                Log.w(TAG, "A removed player could not be dropped from Players")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Sleevesight"
    }
}
