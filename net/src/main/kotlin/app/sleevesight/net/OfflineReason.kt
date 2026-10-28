package app.sleevesight.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException

/**
 * The label a failed or lost connection is reported by ([NetError.Offline.reason]): a type, never
 * the exception's message, which can quote the URL or the host. The release build renames the
 * libraries' classes (R8 made Ktor's `HttpRequestTimeoutException` `tx0`), so a failure's own class
 * name would not read in a problem report from the phone (#665). So:
 *
 * - a timeout is named as one, wherever it is among [e]'s causes;
 * - any other failure by the innermost of its causes that is the platform's own `IOException`
 *   ([isPlatformIoException]): past a library's wrapper (Ktor's body channel's) above it, and short
 *   of what is not an `IOException` below it (Android's `ErrnoException` under a `SocketException`,
 *   `GaiException` under an `UnknownHostException`). Innermost rather than outermost, since OkHttp
 *   wraps an `EOFException` in a plain `IOException`. Without one, by the innermost `IOException`,
 *   or [e] itself, as the platform names its type ([platformTypeName]);
 * - followed by the errno that the causes' messages name, when it is one of [ERRNOS], in brackets
 *   (`SocketException (ECONNABORTED)`, for "Software caused connection abort"): a fixed name of the
 *   system's, picked from a list, so nothing of the message itself, which can hold a host or an
 *   address, is kept.
 */
internal fun offlineReason(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()
    causes.firstNotNullOfOrNull(::timeoutLabel)?.let { return it }
    val named = causes.lastOrNull(::isPlatformIoException) ?: causes.lastOrNull { it is IOException } ?: e
    val errno = causes.firstNotNullOfOrNull { cause -> cause.message?.let(::errnoIn) }
    return platformTypeName(named) + errno?.let { " ($it)" }.orEmpty()
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

private fun errnoIn(message: String): String? =
    ERRNO_WORD
        .findAll(message)
        .map {
            it.value
        }.firstOrNull { it in ERRNOS }

private const val CONNECT_TIMEOUT = "ConnectTimeoutException"
private const val SOCKET_TIMEOUT = "SocketTimeoutException"
private const val REQUEST_TIMEOUT = "HttpRequestTimeoutException"

/** A bound on the causes read, against a chain that loops. */
private const val MAX_CAUSES = 8

private val ERRNO_WORD = Regex("\\bE[A-Z_]+\\b")

/**
 * The errno names a lost connection's message may hold (Android's libcore words a failed call
 * `recvfrom failed: ECONNABORTED (Software caused connection abort)`), and the resolver's.
 */
private val ERRNOS: Set<String> =
    setOf(
        "ECONNABORTED",
        "ECONNREFUSED",
        "ECONNRESET",
        "EHOSTUNREACH",
        "ENETDOWN",
        "ENETRESET",
        "ENETUNREACH",
        "ENONET",
        "ENOTCONN",
        "EPIPE",
        "ETIMEDOUT",
        "EACCES",
        "EPERM",
        "EAI_AGAIN",
        "EAI_FAIL",
        "EAI_NODATA",
        "EAI_NONAME",
    )
