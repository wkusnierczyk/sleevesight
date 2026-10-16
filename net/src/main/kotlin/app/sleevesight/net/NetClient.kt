package app.sleevesight.net

import kotlin.coroutines.cancellation.CancellationException

/**
 * The one HTTP client every source and provider goes through (architecture, Networking). An
 * interface, so the modules above `:net` test against hand-written fakes.
 */
interface NetClient : AutoCloseable {
    /**
     * Sends [request], waiting for its host's rate limit and retrying as [NetConfig.retry] allows.
     * Redirects of a GET are followed here, at most five, each through its target's rate limit; a
     * request that carries credentials follows them within its origin only. Any other redirect is
     * returned as its 3xx response. A cancelled caller cancels the request.
     */
    suspend fun send(request: NetRequest): NetResult<NetResponse>

    /**
     * Sends [request] as [send] does, but hands a 2xx response's body to [onLine] line by line as it
     * arrives, for server-sent events and line-delimited JSON: a line ends at `\n`, `\r\n` or `\r`,
     * and the terminators are not passed on. The [NetResponse] returned then has an empty body. Any
     * other response is read whole, up to [NetRequest.maxBytes], and returned as [send] returns it,
     * without calling [onLine]; redirects are not followed, so a 3xx is returned too.
     *
     * The guarantees of [send] hold. Waits for the host's rate limit and retries happen only before
     * the body: once a line has been passed on, a failure is returned, never retried, whatever the
     * method, so a paid stream is never sent twice. A body longer than [NetRequest.maxBytes], counted
     * across the whole stream, ends it with [NetError.TooLarge]; a connection lost mid-stream is
     * [NetError.Offline]; in both cases [onLine] has seen the lines before. [NetRequest.timeout]
     * bounds the whole exchange, stream included. Each attempt is logged once, when it ends. What
     * [onLine] throws ends the stream, is logged as the attempt stopped by the caller, and is
     * rethrown to the caller as it is.
     *
     * The default reads the response whole through [send] and then splits it, which suits fakes; the
     * client over Ktor streams. The default inherits [send]'s handling of redirects, so a fake that
     * follows them follows them here too.
     */
    suspend fun stream(
        request: NetRequest,
        onLine: suspend (String) -> Unit,
    ): NetResult<NetResponse> {
        val result = send(request)
        val response = (result as? NetResult.Success)?.value?.takeIf { it.isSuccess } ?: return result
        LineSplitter(onLine).apply {
            feed(response.bytes)
            finish()
        }
        return NetResult.Success(response.withoutBody())
    }
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
