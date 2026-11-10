package app.sleevesight.ai

import app.sleevesight.core.ai.CompletionEvent

/**
 * [this] without the errors' [CompletionEvent.Error.detail], which only the diagnostics read (#1164):
 * for the tests of the kinds, which the detail's own tests leave to them.
 */
internal fun List<CompletionEvent>.withoutDetail(): List<CompletionEvent> =
    map { if (it is CompletionEvent.Error) it.copy(detail = null) else it }
