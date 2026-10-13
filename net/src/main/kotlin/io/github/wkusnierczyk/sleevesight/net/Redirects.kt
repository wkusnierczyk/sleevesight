package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url

private val REDIRECTS =
    setOf(
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect,
    ).map { it.value }.toSet()

/**
 * The request a redirect leads to, or null if [result] is not one to follow. Only a GET is followed,
 * to a non-blank `Location` resolved against the URL that was [sent] (RFC 3986, [resolveReference]),
 * and never with user info. A request that [carriesCredentials] follows only redirects within its
 * origin; to another origin the 3xx is returned. Other requests follow anywhere: they have no
 * secrets and no headers named like keys to leave behind.
 */
internal fun redirectFrom(
    request: NetRequest,
    sent: Url,
    result: NetResult<NetResponse>,
    carriesCredentials: Boolean,
): NetRequest? {
    val response = (result as? NetResult.Success)?.value
    val followed = response?.takeIf { it.status in REDIRECTS && request.method == NetMethod.GET }
    val location = followed?.header("Location")?.trim()?.takeIf { it.isNotEmpty() }
    val target =
        location
            ?.let { resolveReference(sent.toString(), it) }
            ?.let(::parseHttpUrl)
            ?.let {
                URLBuilder(it)
                    .apply {
                        user = null
                        password = null
                        fragment = ""
                    }.build()
            }
    val sameOrigin = target != null && sameOrigin(sent, target)
    return if (target == null || (carriesCredentials && !sameOrigin)) {
        null
    } else {
        NetRequest(
            url = target.toString(),
            headers = request.headers,
            secrets = request.secrets.forRedirect(carriesCredentials),
            timeout = request.timeout,
            maxBytes = request.maxBytes,
        )
    }
}

private fun sameOrigin(
    a: Url,
    b: Url,
): Boolean =
    a.protocol == b.protocol &&
        NetConfig.normalizeHost(a.host) == NetConfig.normalizeHost(b.host) &&
        a.port == b.port
