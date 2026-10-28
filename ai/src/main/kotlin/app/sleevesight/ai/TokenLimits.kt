package app.sleevesight.ai

import app.sleevesight.core.ai.CompletionRequest

/**
 * [request]'s token limit as sent to its model: as asked when the model [takesEffort], else at most
 * [CompletionRequest.DEFAULT_MAX_TOKENS]. The limit grows with the effort
 * (`CompletionRequest.maxTokensFor`), and a model that takes no effort control is an older one,
 * whose output maximum, which the client does not know, can be as low as that (#665).
 */
internal fun maxTokensSent(
    request: CompletionRequest,
    takesEffort: Boolean,
): Int = if (takesEffort) request.maxTokens else minOf(request.maxTokens, CompletionRequest.DEFAULT_MAX_TOKENS)
