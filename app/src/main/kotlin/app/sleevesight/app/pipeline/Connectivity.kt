package app.sleevesight.app.pipeline

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the phone is online, for [PipelineHost] (#192): true while its default network has
 * internet access that Android has validated, so a network still connecting, or behind a captive
 * portal, is not yet online, and its return is the moment requests can succeed. A network Android
 * never validates never counts. Emits the state when collected, then each change; registered only
 * while collected (`ACCESS_NETWORK_STATE`).
 *
 * The callback is registered before the state is read, and the reading is sent only if no
 * callback has come meanwhile ([FirstReading]): a network lost or found in between is then
 * reported by its callback, and a stale reading never overwrites it.
 */
fun ConnectivityManager.online(): Flow<Boolean> =
    callbackFlow {
        val readings = FirstReading { trySend(it) }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    readings.changed(validated(capabilities))
                }

                override fun onLost(network: Network) {
                    readings.changed(false)
                }
            }
        registerDefaultNetworkCallback(callback)
        // With no default network, the callback says nothing until one comes.
        readings.read(validated(getNetworkCapabilities(activeNetwork)))
        awaitClose { unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

/**
 * The connection's state as [send] is told it: each change from the callback, and a first
 * reading, sent only if no change came before it, so a reading taken before a change never
 * overwrites it. Thread-safe: the callback runs on its own thread.
 */
internal class FirstReading(
    private val send: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var changed = false

    /** The callback reported [online]. */
    fun changed(online: Boolean) =
        synchronized(lock) {
            changed = true
            send(online)
        }

    /** The state read once the callback was registered: sent unless a change came first. */
    fun read(online: Boolean) =
        synchronized(lock) {
            if (!changed) send(online)
        }
}

private fun validated(capabilities: NetworkCapabilities?): Boolean =
    capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
