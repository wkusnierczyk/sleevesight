package app.sleevesight.net

import io.ktor.http.Url

/*
 * `:net` protects the caller's credentials. A value a server chose and handed back (a token in a
 * `Location`) is not ours to keep from other origins, only from our logs. Nothing here matches
 * values against URLs; matching keeps losing to new spellings. Two fixed rules instead:
 *
 * - staysInOrigin, decided from the caller's request and carried through the chain: the chain
 *   follows no redirect to another origin.
 * - hiddenInLog, per hop and sticky: the log shows neither the hop's path nor its query names. Only
 *   the caller's own URL is provably public, so every hop after a followed redirect is hidden.
 */

/**
 * Whether the caller's [request] carries credentials, so its chain must stay in its origin: any
 * [NetRequest.secrets], a header or query parameter named like a key ([Redaction.isSensitive]),
 * user info in its URL, or [NetRequest.sensitiveUrl].
 */
internal fun staysInOrigin(request: NetRequest): Boolean =
    request.secrets.query.isNotEmpty() ||
        request.secrets.headers.isNotEmpty() ||
        request.headers.keys.any(Redaction::isSensitive) ||
        urlIsSensitive(request)

/**
 * Whether the log hides [request]'s path and query names: its URL may hold a credential
 * ([urlIsSensitive]), or it is a hop after a followed redirect ([NetSecrets.hideInLog]), whose URL
 * the server chose. A first hop whose credentials are only in [NetRequest.secrets] or in headers
 * named like keys shows its path, which is public by contract.
 */
internal fun hiddenInLog(request: NetRequest): Boolean = request.secrets.hideInLog || urlIsSensitive(request)

/** [NetRequest.sensitiveUrl], user info, or a query parameter named like a key, in the URL or in `query`. */
private fun urlIsSensitive(request: NetRequest): Boolean =
    request.sensitiveUrl ||
        request.query.keys.any(Redaction::isSensitive) ||
        parseHttpUrl(request.url)?.let(::urlBearsCredentials) == true

/** Whether [url] itself has user info or a query parameter named like a key. */
private fun urlBearsCredentials(url: Url): Boolean =
    url.user != null || url.password != null || url.parameters.names().any(Redaction::isSensitive)
