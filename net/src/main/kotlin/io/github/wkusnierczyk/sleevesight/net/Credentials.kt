package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.Url

/**
 * Whether [request] is credential-bearing: it has any [NetRequest.secrets], a header or query
 * parameter named like a key ([Redaction.isSensitive]), in the URL or not, user info in the URL, or
 * [NetRequest.sensitiveUrl]; or an earlier hop of the same chain was ([NetSecrets.carried]).
 *
 * Nothing here matches values against URLs; matching keeps losing to new spellings. A
 * credential-bearing hop instead gets fixed rules: the log shows neither its path nor its query
 * names, and it follows no redirect to another origin. `:net` decides this again on every hop, and
 * once a chain is credential-bearing it stays so.
 */
internal fun carriesCredentials(request: NetRequest): Boolean =
    request.secrets.carried ||
        request.sensitiveUrl ||
        request.secrets.query.isNotEmpty() ||
        request.secrets.headers.isNotEmpty() ||
        request.headers.keys.any(Redaction::isSensitive) ||
        request.query.keys.any(Redaction::isSensitive) ||
        parseHttpUrl(request.url)?.let(::urlBearsCredentials) == true

/** Whether [url] itself has user info or a query parameter named like a key. */
internal fun urlBearsCredentials(url: Url): Boolean =
    url.user != null || url.password != null || url.parameters.names().any(Redaction::isSensitive)
