package app.sleevesight.ai

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.Breakoff
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.Usage
import app.sleevesight.net.NetError

/**
 * The end of a reply that had begun and broke off: after the transport failed with [error], or,
 * when it is null, after the stream ended without the service's end of the reply. [usage] is what
 * the service had counted by then. A connection lost and a timeout are told apart, by `:net`'s
 * label, for the card and the diagnostics (#665).
 */
internal fun brokeOff(
    error: NetError?,
    usage: Usage,
): CompletionEvent.Error {
    val cause =
        when {
            error == null -> BreakoffCause.NO_END
            error is NetError.TooLarge -> BreakoffCause.TOO_LARGE
            error is NetError.Offline && error.timedOut -> BreakoffCause.TIMED_OUT
            else -> BreakoffCause.CONNECTION_LOST
        }
    val detail = (error as? NetError.Offline)?.reason
    return CompletionEvent.Error(AiErrorKind.INCOMPLETE, breakoff = Breakoff(cause, usage, detail))
}
