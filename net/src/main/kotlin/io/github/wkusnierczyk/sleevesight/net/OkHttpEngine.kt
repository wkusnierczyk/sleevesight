package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Interceptor

/**
 * The HTTP engine: the one per-platform part of `:net` (architecture, Multiplatform readiness). An
 * iOS target would supply Darwin's here. Timeouts are set through Ktor, not OkHttp.
 *
 * OkHttp must send nothing that `:net`'s rate limits and retry policy did not decide:
 * - no redirects (`:net` follows them itself) and no retry on a failed connection, which would
 *   replay a paid POST;
 * - request bodies are one-shot (`KtorNetClient` sends them as a stream), so OkHttp cannot replay
 *   them for any follow-up;
 * - a 503 with `Retry-After: 0`, which OkHttp would repeat at once, outside the limiter, is passed
 *   up with a one-second `Retry-After` instead, for `:net` to retry.
 *
 * What remains is OkHttp's retry of a 421 on a coalesced HTTP/2 connection, for bodiless requests
 * only: one repeat to the same host, after a refusal.
 */
internal fun okHttpEngine(): HttpClientEngine =
    OkHttp.create {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
        addNetworkInterceptor(noImmediateRetry)
    }

private const val SERVICE_UNAVAILABLE = 503

private val noImmediateRetry =
    Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == SERVICE_UNAVAILABLE && response.header("Retry-After")?.trim() == "0") {
            response.newBuilder().header("Retry-After", "1").build()
        } else {
            response
        }
    }
