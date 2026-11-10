package app.sleevesight.ai

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.net.NetError
import app.sleevesight.net.NetResponse
import kotlin.time.Duration

/**
 * The kind of a request to which no answer came at all (#1164): a timeout is [AiErrorKind.TIMED_OUT];
 * no network to send on ([NetError.Offline.noNetwork]) is [AiErrorKind.OFFLINE], "no connection";
 * any other failure, on a network that was there (a connection reset, refused or closed, a TLS
 * failure), is [AiErrorKind.CONNECTION_FAILED].
 */
internal fun kindOfOffline(error: NetError.Offline): AiErrorKind =
    when {
        error.timedOut -> AiErrorKind.TIMED_OUT
        error.noNetwork -> AiErrorKind.OFFLINE
        else -> AiErrorKind.CONNECTION_FAILED
    }

/**
 * What failed, for `CompletionEvent.Error.detail` (#1164): `:net`'s fixed labels and statuses only,
 * never a message.
 */
internal fun detailOf(error: NetError): String =
    when (error) {
        is NetError.Offline -> error.reason
        is NetError.RateLimited -> "rate limited"
        is NetError.Unauthorized -> "HTTP ${error.status}"
        is NetError.Server -> "HTTP ${error.status}"
        is NetError.Parse -> error.reason
        is NetError.Protocol -> error.reason
        is NetError.TooLarge -> "longer than ${error.limit} bytes"
        is NetError.InvalidRequest -> "invalid ${error.field}"
    }

/**
 * The wait [error] asked for, for `CompletionEvent.Error.retryAfter`: a rate limit's, or an
 * overloaded service's 5xx with a `Retry-After` (#1164), which the AI calls' retry waits out.
 */
internal fun retryAfterOf(error: NetError): Duration? =
    when (error) {
        is NetError.RateLimited -> error.retryAfter
        is NetError.Server -> error.retryAfter
        else -> null
    }

/** The error of a request to which no usable answer came, as [kind], with its wait and what failed. */
internal fun failedWith(
    error: NetError,
    kind: AiErrorKind,
): CompletionEvent.Error = CompletionEvent.Error(kind, retryAfterOf(error), detail = detailOf(error))

/** An error answer's status, for `CompletionEvent.Error.detail` (#1164). */
internal fun detailOf(response: NetResponse): String = "HTTP ${response.status}"

/**
 * The service's own code for an error it sent in the stream, for `CompletionEvent.Error.detail`
 * (#1164): kept only as a code (letters, digits, `_`, `.`, `-`, at most 64), so no message can pass.
 */
internal fun codeDetail(code: String?): String =
    "error in the stream" + code?.takeIf(CODE::matches)?.let { ": $it" }.orEmpty()

private val CODE = Regex("[A-Za-z0-9_.-]{1,64}")

/** The detail of a stream's event that was not JSON the client can read (#1164). */
internal const val UNREADABLE_EVENT: String = "unreadable event"

/** The kinds whose error answer keeps the wait it asks for: a rate limit, and an overload (#1164). */
internal val WAITED: Set<AiErrorKind> = setOf(AiErrorKind.RATE_LIMITED, AiErrorKind.OVERLOADED)
