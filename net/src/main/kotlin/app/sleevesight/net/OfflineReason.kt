package app.sleevesight.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/**
 * The label a failed or lost connection is reported by ([NetError.Offline.reason]): a type, never
 * the exception's message, which can quote the URL. A timeout is named as such, wherever it is in
 * [e]'s causes; any other failure by the type of its innermost cause, which a wrapper such as the
 * body channel's own hides, as the platform names it ([platformTypeName]). The release build
 * renames the libraries' classes (R8 made Ktor's `HttpRequestTimeoutException` `tx0`), so a
 * failure's own class name would not read in a problem report from the phone (#665).
 */
internal fun offlineReason(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()
    return causes.firstNotNullOfOrNull(::timeoutLabel) ?: platformTypeName(causes.last())
}

/** The labels of the client's timeouts, which [NetError.Offline.timedOut] reads. */
internal val TIMEOUT_REASONS: Set<String> = setOf(CONNECT_TIMEOUT, SOCKET_TIMEOUT, REQUEST_TIMEOUT)

private fun timeoutLabel(e: Throwable): String? =
    when (e) {
        is ConnectTimeoutException -> CONNECT_TIMEOUT
        is SocketTimeoutException -> SOCKET_TIMEOUT
        is HttpRequestTimeoutException -> REQUEST_TIMEOUT
        else -> null
    }

private const val CONNECT_TIMEOUT = "ConnectTimeoutException"
private const val SOCKET_TIMEOUT = "SocketTimeoutException"
private const val REQUEST_TIMEOUT = "HttpRequestTimeoutException"

/** A bound on the causes read, against a chain that loops. */
private const val MAX_CAUSES = 8
