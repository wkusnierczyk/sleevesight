package app.sleevesight.net

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
 * and never with user info. A chain that [staysInOrigin] follows redirects within its origin only;
 * to another origin the 3xx is returned. The next hop is hidden in the log: its URL is the server's
 * choice, not the caller's, so it is not provably public.
 */
internal fun redirectFrom(
    request: NetRequest,
    sent: Url,
    result: NetResult<NetResponse>,
    staysInOrigin: Boolean,
): NetRequest? {
    val response = (result as? NetResult.Success)?.value
    val followed = response?.takeIf { it.status in REDIRECTS && request.method == NetMethod.GET }
    val location = followed?.header("Location")?.trim()?.takeIf { it.isNotEmpty() }
    val resolved = location?.let { resolveReference(sent.toString(), it) }?.let(::parseHttpUrl)
    val target =
        resolved?.let {
            URLBuilder(it)
                .apply {
                    user = null
                    password = null
                    fragment = ""
                }.build()
        }
    val sameOrigin = target != null && sameOrigin(sent, target)
    return if (target == null || (staysInOrigin && !sameOrigin)) {
        null
    } else {
        NetRequest(
            url = target.toString(),
            headers = request.headers,
            secrets = request.secrets.forRedirect(),
            timeout = request.timeout,
            maxBytes = request.maxBytes,
            sensitiveUrl = request.sensitiveUrl,
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
