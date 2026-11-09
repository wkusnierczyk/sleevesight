package app.sleevesight.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * This flow, collected on [dispatcher] and handed on in order, each value before any failure that
 * follows it: how the settings' and the keys' stores read DataStore off the caller's dispatcher
 * (#1107). Not `flowOn`: its buffer can drop a value that came just before a failure, since the
 * failure cancels the collection while the value waits to be taken.
 */
internal fun <T> Flow<T>.collectedOn(dispatcher: CoroutineDispatcher): Flow<T> {
    val upstream = this
    return flow {
        coroutineScope {
            val values = Channel<T>(Channel.RENDEZVOUS)
            launch(dispatcher) {
                // Closed after the values, as the collection ends: with its failure, if any.
                values.close(runCatching { upstream.collect { values.send(it) } }.exceptionOrNull())
            }
            for (value in values) emit(value)
        }
    }
}
