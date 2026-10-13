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
 * and never with user info. To another origin, the secrets and the headers named like keys stay
 * behind, and a target that contains any credential [material] (a key echoed into the `Location`)
 * is not followed at all: the 3xx is returned.
 */
internal fun redirectFrom(
    request: NetRequest,
    sent: Url,
    result: NetResult<NetResponse>,
    material: Set<String>,
): NetRequest? {
    val response = (result as? NetResult.Success)?.value
    val followed = response?.takeIf { it.status in REDIRECTS && request.method == NetMethod.GET }
    val location = followed?.header("Location")?.trim()?.takeIf { it.isNotEmpty() }
    val resolved = location?.let { resolveReference(sent.toString(), it) }
    val target =
        resolved?.let(::parseHttpUrl)?.let {
            URLBuilder(it)
                .apply {
                    user = null
                    password = null
                    fragment = ""
                }.build()
        }
    val sameOrigin = target != null && sameOrigin(sent, target)
    val leaks = !sameOrigin && resolved != null && containsMaterial(resolved, material)
    return if (target == null || leaks) {
        null
    } else {
        NetRequest(
            url = target.toString(),
            headers = if (sameOrigin) request.headers else request.headers.filterKeys { !Redaction.isSensitive(it) },
            secrets = request.secrets.forRedirect(sameOrigin, material),
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
