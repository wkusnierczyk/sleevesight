package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.takeFrom

private val REDIRECTS =
    setOf(
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect,
    ).map { it.value }.toSet()

/**
 * The request a redirect leads to, or null if [result] is not one to follow: only a GET is
 * followed, to an http(s) `Location`. To another origin, the secrets and the headers named like
 * keys stay behind.
 */
internal fun redirectFrom(
    request: NetRequest,
    result: NetResult<NetResponse>,
): NetRequest? {
    val response = (result as? NetResult.Success)?.value
    val followed = response?.takeIf { it.status in REDIRECTS && request.method == NetMethod.GET }
    val location = followed?.header("Location")
    val from = parseHttpUrl(request.url)
    val target = if (location != null && from != null) resolve(from, location) else null
    if (from == null || target == null) return null
    val sameOrigin = sameOrigin(from, target)
    return NetRequest(
        url = target.toString(),
        headers = if (sameOrigin) request.headers else request.headers.filterKeys { !Redaction.isSensitive(it) },
        secrets = request.secrets.forRedirect(sameOrigin),
        timeout = request.timeout,
    )
}

/** [location], relative or absolute, against [from]; null unless it is an http(s) URL. */
@Suppress("SwallowedException") // A malformed Location is not followed; its text is not kept.
private fun resolve(
    from: Url,
    location: String,
): Url? =
    try {
        parseHttpUrl(URLBuilder(from).takeFrom(location).buildString())
    } catch (e: URLParserException) {
        null
    }

private fun sameOrigin(
    a: Url,
    b: Url,
): Boolean =
    a.protocol == b.protocol &&
        NetConfig.normalizeHost(a.host) == NetConfig.normalizeHost(b.host) &&
        a.port == b.port
