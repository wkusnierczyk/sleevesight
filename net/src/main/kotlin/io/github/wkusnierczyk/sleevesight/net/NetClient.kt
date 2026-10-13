package io.github.wkusnierczyk.sleevesight.net

import kotlin.coroutines.cancellation.CancellationException

/**
 * The one HTTP client every source and provider goes through (architecture, Networking). An
 * interface, so the modules above `:net` test against hand-written fakes.
 */
interface NetClient : AutoCloseable {
    /**
     * Sends [request], waiting for its host's rate limit and retrying as [NetConfig.retry] allows.
     * Redirects of a GET are followed here, at most five, each through its target's rate limit, and
     * to another origin without the request's secrets or headers named like keys; a redirected POST
     * is returned as its 3xx response. A cancelled caller cancels the request.
     */
    suspend fun send(request: NetRequest): NetResult<NetResponse>
}

/**
 * The client over the OkHttp engine, logging each attempt to [log], redacted.
 *
 * The rate limiters belong to the instance, so a process makes exactly one: the app's `AppGraph`
 * and the CLI each create it once and inject it into every adapter and provider. Two instances
 * would each allow MusicBrainz a request a second.
 */
fun NetClient(
    config: NetConfig,
    log: NetLog = NetLog.NONE,
): NetClient = KtorNetClient(config, okHttpEngine(), log)

/**
 * Sends [request] and reads the response with [parse]. Anything [parse] throws is a
 * [NetError.Parse] carrying only the exception's type, since its message can quote the response or
 * the request; a malformed response is a typed error, not a crash.
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
                NetResult.Failure(NetError.Parse(e::class.simpleName ?: "Exception"))
            }
        }
    }
