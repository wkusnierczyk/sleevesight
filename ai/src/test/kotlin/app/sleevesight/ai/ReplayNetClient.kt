package app.sleevesight.ai

import app.sleevesight.fixtures.Fixtures
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult

/**
 * A [NetClient] that answers from recordings and canned responses, and keeps the requests it got.
 * Streams go through `NetClient.stream`'s default, which splits the whole body into lines, as the
 * client over Ktor does while it arrives.
 */
class ReplayNetClient(
    private val answer: (NetRequest) -> NetResult<NetResponse>,
) : NetClient {
    val requests = mutableListOf<NetRequest>()

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        requests += request
        return answer(request)
    }

    override fun close() = Unit

    companion object {
        /** A response with [body] and [status]. */
        fun ok(
            body: String,
            status: Int = 200,
        ): NetResult<NetResponse> = NetResult.Success(NetResponse(status, emptyMap(), body.encodeToByteArray()))

        fun failure(error: NetError): NetResult<NetResponse> = NetResult.Failure(error)

        /** A recorded response body under `fixtures/http/`. */
        fun recorded(path: String): String = Fixtures.text("http/$path")
    }
}
