package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * The HTTP engine: the one per-platform part of `:net` (architecture, Multiplatform readiness). An
 * iOS target would supply Darwin's here. Timeouts are set through Ktor, not OkHttp.
 *
 * OkHttp sends nothing that `:net`'s rate limits and retry policy did not decide:
 * - no redirects (`:net` follows them itself) and no retry on a failed connection, which would
 *   resend a request silently;
 * - request bodies are one-shot (`KtorNetClient` sends them as a stream), so OkHttp cannot replay
 *   them for any follow-up;
 * - HTTP/1.1 only, so there is no coalesced HTTP/2 connection whose 421 OkHttp would retry;
 * - a 503's `Retry-After`, which OkHttp reads to repeat the request at once when it is zero, is moved
 *   to [ORIGINAL_RETRY_AFTER] before OkHttp sees it, whatever its form, and `:net` reads it there.
 *
 * [configure] runs last; it is for tests that undo one guard to show another holds.
 */
internal fun okHttpEngine(configure: OkHttpClient.Builder.() -> Unit = {}): HttpClientEngine =
    OkHttp.create {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            protocols(listOf(Protocol.HTTP_1_1))
            configure()
        }
        addNetworkInterceptor(hideRetryAfterOn503)
    }

/** Where a 503's `Retry-After` travels past OkHttp. */
internal const val ORIGINAL_RETRY_AFTER = "X-Sleevesight-Retry-After"

private const val SERVICE_UNAVAILABLE = 503
private const val RETRY_AFTER = "Retry-After"

private val hideRetryAfterOn503 =
    Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val retryAfter = response.headers(RETRY_AFTER).firstOrNull()
        if (response.code == SERVICE_UNAVAILABLE && retryAfter != null) {
            response
                .newBuilder()
                .removeHeader(RETRY_AFTER)
                .header(ORIGINAL_RETRY_AFTER, retryAfter)
                .build()
        } else {
            response
        }
    }
