package io.github.wkusnierczyk.sleevesight.net

/**
 * Whether [request] carries credentials: any value in [NetRequest.secrets]; a header or query
 * parameter named like a key ([Redaction.isSensitive]), in the URL or not; user info in the URL; or
 * an earlier hop of the same request that did ([NetSecrets.carried]).
 *
 * Nothing here matches values against URLs; matching keeps losing to new encodings. A request that
 * carries credentials instead gets fixed rules: its path is not logged, and it follows no redirect
 * to another origin.
 */
internal fun carriesCredentials(request: NetRequest): Boolean {
    val url = parseHttpUrl(request.url)
    return request.secrets.carried ||
        request.secrets.query.isNotEmpty() ||
        request.secrets.headers.isNotEmpty() ||
        request.headers.keys.any(Redaction::isSensitive) ||
        request.query.keys.any(Redaction::isSensitive) ||
        url?.parameters?.names()?.any(Redaction::isSensitive) == true ||
        url?.user != null ||
        url?.password != null
}
