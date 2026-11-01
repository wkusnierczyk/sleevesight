package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason

/**
 * Why a model's reply gave no card, as the fixed label of [InsightState.Failed.reason] that the
 * card shows in words and the problem report names: an [AiErrorKind]'s name in lower case, or one
 * of the labels here. The insight and the genre reasons share them.
 */
object ReplyFailures {
    /** The model declined to write it. */
    const val DECLINED: String = "declined"

    /** The reply ended before its end, and the client cannot tell more ([AiErrorKind.INCOMPLETE]). */
    const val INCOMPLETE: String = "incomplete"

    /** The connection was lost mid-reply ([BreakoffCause.CONNECTION_LOST]). */
    const val CONNECTION_LOST: String = "connection_lost"

    /** The client stopped waiting for the reply ([BreakoffCause.TIMED_OUT]). */
    const val TIMED_OUT: String = "timed_out"

    /**
     * The reply reached its token limit before any text ([StopReason.MAX_TOKENS] with none): the
     * model's reasoning, which counts against the limit, used all of it.
     */
    const val LENGTH_LIMIT: String = "length_limit"

    /**
     * The reply revised itself in its text (`SelfRevision`, #798), and so did the one asked for once
     * more: neither is shown.
     */
    const val SELF_REVISED: String = "self_revised"

    /** The reply ended with no text, for any other reason. */
    const val EMPTY: String = "empty"

    /**
     * [error]'s label: its kind's, or for a reply that broke off, how it did, where that tells the
     * listener something (#665). A reply cut off as too long, or a stream that simply ended, keeps
     * [INCOMPLETE]: the diagnostics name which ([CompletionEvent.Error.breakoff]).
     */
    fun of(error: CompletionEvent.Error): String =
        when (error.breakoff?.cause) {
            BreakoffCause.CONNECTION_LOST -> CONNECTION_LOST
            BreakoffCause.TIMED_OUT -> TIMED_OUT
            BreakoffCause.NO_END, BreakoffCause.TOO_LARGE, null -> error.kind.name.lowercase()
        }

    /**
     * The label of a reply that ended ([done]) with no text but whitespace, which is no card to show
     * or keep; null when [text] has some. A refusal is [DECLINED] whatever its text.
     */
    fun ofBlank(
        done: CompletionEvent.Done,
        text: String,
    ): String? =
        when {
            done.stopReason == StopReason.REFUSAL || text.isNotBlank() -> null
            done.stopReason == StopReason.MAX_TOKENS -> LENGTH_LIMIT
            else -> EMPTY
        }
}
