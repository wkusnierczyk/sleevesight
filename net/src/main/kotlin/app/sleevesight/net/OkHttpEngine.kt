package app.sleevesight.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.ProtocolException

/**
 * The HTTP engine: the one per-platform part of `:net` (architecture, Multiplatform readiness). An
 * iOS target would supply Darwin's here. Timeouts are set through Ktor, not OkHttp.
 *
 * OkHttp sends nothing that `:net`'s rate limits and retry policy did not decide ([applyGuards]),
 * and a 503's `Retry-After`, which OkHttp reads to repeat the request at once when it is zero, is
 * moved to [ORIGINAL_RETRY_AFTER] before OkHttp sees it, whatever its form; `:net` reads it there.
 * A server's own copy of that header is removed first. OkHttp's `ProtocolException` (a 407 from a
 * server that is not a proxy) becomes [EngineProtocolException], so `:net` does not retry it.
 *
 * [configure] runs last; it is for tests that undo one guard to show another holds.
 */
internal fun okHttpEngine(configure: OkHttpClient.Builder.() -> Unit = {}): HttpClientEngine =
    OkHttp.create {
        config {
            applyGuards()
            configure()
        }
        addInterceptor(protocolExceptions)
        addNetworkInterceptor(hideRetryAfterOn503)
    }

/**
 * - no redirects (`:net` follows them itself) and no retry on a failed connection, which would
 *   resend a request silently;
 * - HTTP/1.1 only, so there is no coalesced HTTP/2 connection whose 421 OkHttp would retry.
 *
 * Request bodies are one-shot besides (`KtorNetClient` sends them as a stream), so OkHttp cannot
 * replay them for any follow-up.
 */
internal fun OkHttpClient.Builder.applyGuards() {
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
    protocols(listOf(Protocol.HTTP_1_1))
}

/** Where a 503's `Retry-After` travels past OkHttp. */
internal const val ORIGINAL_RETRY_AFTER = "X-Sleevesight-Retry-After"

private const val SERVICE_UNAVAILABLE = 503
private const val RETRY_AFTER = "Retry-After"

private val hideRetryAfterOn503 =
    Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val builder = response.newBuilder().removeHeader(ORIGINAL_RETRY_AFTER)
        val retryAfter = response.headers(RETRY_AFTER).firstOrNull()
        if (response.code == SERVICE_UNAVAILABLE && retryAfter != null) {
            builder.removeHeader(RETRY_AFTER).header(ORIGINAL_RETRY_AFTER, retryAfter)
        }
        builder.build()
    }

// The message is dropped on purpose: it can quote the request.
@Suppress("SwallowedException")
private val protocolExceptions =
    Interceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: ProtocolException) {
            throw EngineProtocolException(e::class.simpleName ?: "ProtocolException")
        }
    }

/**
 * [e]'s type as the platform names it, for `offlineReason`: its class's simple name when that is a
 * class of Java or Kotlin, else that of its nearest superclass that is (OkHttp's and Okio's own
 * become `IOException` or the like). R8 renames the app's and the libraries' classes in the release
 * build, never the platform's, so the name reads the same in a problem report from the phone as in
 * a test (#665).
 */
internal fun platformTypeName(e: Throwable): String {
    var type: Class<*> = e.javaClass
    while (PLATFORM_PACKAGES.none { type.name.startsWith(it) }) {
        type = type.superclass ?: break
    }
    return type.simpleName
}

private val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")
