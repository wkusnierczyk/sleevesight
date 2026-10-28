package app.sleevesight.net

import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url

private val REDIRECTS =
    setOf(
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect,
    ).map { it.value }.toSet()

/** What a redirect leads to (see [redirectFrom]). */
internal sealed interface Redirect {
    /** The next hop, to send. */
    data class Follow(
        val request: NetRequest,
    ) : Redirect

    /**
     * A redirect from `https` to plain `http`, never followed (#696): the next hop would travel
     * unencrypted, on the JVM at once, and on the phone to the manifest's refusal, which OkHttp
     * reports as a failed connection, so as offline, and retried. Ended as [error] instead.
     */
    data object ToHttp : Redirect {
        val error: NetError = NetError.Protocol("redirect to http refused")
    }
}

/**
 * The redirect [result] is, or null if it is not one to follow. Only a GET is followed, to a
 * non-blank `Location` resolved against the URL that was [sent] (RFC 3986, [resolveReference]), and
 * never with user info. A redirect from `https` to plain `http` is [Redirect.ToHttp], never followed,
 * in or out of the origin. A chain that [staysInOrigin] follows redirects within its origin only; to
 * another origin the 3xx is returned. The next hop is hidden in the log: its URL is the server's
 * choice, not the caller's, so it is not provably public.
 */
internal fun redirectFrom(
    request: NetRequest,
    sent: Url,
    result: NetResult<NetResponse>,
    staysInOrigin: Boolean,
): Redirect? {
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
    return when {
        target == null -> null
        sent.protocol == URLProtocol.HTTPS && target.protocol != URLProtocol.HTTPS -> Redirect.ToHttp
        staysInOrigin && !sameOrigin -> null
        else ->
            Redirect.Follow(
                NetRequest(
                    url = target.toString(),
                    headers = request.headers,
                    secrets = request.secrets.forRedirect(),
                    timeout = request.timeout,
                    maxBytes = request.maxBytes,
                    sensitiveUrl = request.sensitiveUrl,
                ),
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
