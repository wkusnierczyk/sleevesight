package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private const val STREAM_CHUNK = 8 * 1024

/**
 * Passes [response]'s body to [onLine] line by line as it arrives ([LineSplitter]), up to
 * [maxBytes] in all. Returns null when the body ended, or why it did not: [NetError.TooLarge], or
 * [NetError.Offline] for a connection lost mid-stream, reduced to the exception's type, since its
 * message can quote the URL. The lines before a failure have reached [onLine].
 */
@Suppress("SwallowedException")
internal suspend fun readLines(
    response: HttpResponse,
    maxBytes: Long,
    onLine: suspend (String) -> Unit,
): NetError? {
    val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    var failure: NetError? = NetError.TooLarge(maxBytes).takeIf { declared != null && declared > maxBytes }
    val splitter = LineSplitter { line -> deliver(line, onLine) }
    val channel = response.bodyAsChannel()
    val chunk = ByteArray(STREAM_CHUNK)
    var total = 0L
    try {
        while (failure == null) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            total += read
            if (total > maxBytes) failure = NetError.TooLarge(maxBytes) else splitter.feed(chunk, read)
        }
        if (failure == null) splitter.finish()
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        failure = NetError.Offline(e::class.simpleName ?: "IOException")
    }
    return failure
}

/**
 * Passes [line] to [onLine]. What [onLine] throws travels out as [ConsumerFailure], past the
 * handlers that would take an IOException or an IllegalArgumentException for the engine's own;
 * `KtorNetClient.stream` rethrows the original.
 */
@Suppress("TooGenericExceptionCaught") // Whatever the caller throws is the caller's, and goes back to it.
private suspend fun deliver(
    line: String,
    onLine: suspend (String) -> Unit,
) {
    try {
        onLine(line)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ConsumerFailure(e)
    }
}

/** An exception from the caller's line handler, on its way back to the caller. */
internal class ConsumerFailure(
    val failure: Throwable,
) : RuntimeException()
