package io.github.wkusnierczyk.sleevesight.net

import kotlin.coroutines.cancellation.CancellationException

/**
 * The one HTTP client every source and provider goes through (architecture, Networking). An
 * interface, so the modules above `:net` test against hand-written fakes.
 */
interface NetClient : AutoCloseable {
    /**
     * Sends [request], waiting for its host's rate limit and retrying as [NetConfig.retry] allows.
     * A cancelled caller cancels the request.
     */
    suspend fun send(request: NetRequest): NetResult<NetResponse>
}

/** The client over the OkHttp engine, logging each attempt to [log], redacted. */
fun NetClient(
    config: NetConfig,
    log: NetLog = NetLog.NONE,
): NetClient = KtorNetClient(config, okHttpEngine(), log)

/**
 * Sends [request] and reads the response with [parse]. Anything [parse] throws is a
 * [NetError.Parse], so a malformed response is a typed error, not a crash.
 */
@Suppress("TooGenericExceptionCaught") // Any parser failure, whatever its type, is a Parse error.
suspend fun <T> NetClient.fetch(
    request: NetRequest,
    parse: (NetResponse) -> T,
): NetResult<T> =
    when (val result = send(request)) {
        is NetResult.Failure -> {
            result
        }

        is NetResult.Success -> {
            try {
                NetResult.Success(parse(result.value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetResult.Failure(NetError.Parse(e.message ?: e::class.simpleName ?: "unreadable response"))
            }
        }
    }
