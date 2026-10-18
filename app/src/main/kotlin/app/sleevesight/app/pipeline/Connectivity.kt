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
 * portal, is not yet online, and its return is the moment requests can succeed. Emits the state
 * when collected, then each change; registered only while collected (`ACCESS_NETWORK_STATE`).
 */
fun ConnectivityManager.online(): Flow<Boolean> =
    callbackFlow {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    trySend(validated(capabilities))
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
        // With no default network, the callback says nothing until one comes.
        trySend(validated(getNetworkCapabilities(activeNetwork)))
        registerDefaultNetworkCallback(callback)
        awaitClose { unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

private fun validated(capabilities: NetworkCapabilities?): Boolean =
    capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
