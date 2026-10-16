package app.sleevesight.sources.web

import app.sleevesight.fixtures.Fixtures
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult

/**
 * A [NetClient] that answers every request with one canned result and keeps the requests it was
 * sent, so a test can check them. No network.
 */
internal class ReplayNetClient(
    private val answer: NetResult<NetResponse>,
) : NetClient {
    val sent = mutableListOf<NetRequest>()

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        sent += request
        return answer
    }

    override fun close() = Unit

    companion object {
        /** A client that answers with the recorded response `fixtures/http/[path]`, status 200. */
        fun recorded(path: String): ReplayNetClient = ok(Fixtures.text("http/$path"))

        fun ok(body: String): ReplayNetClient = status(200, body)

        fun status(
            status: Int,
            body: String,
        ): ReplayNetClient =
            ReplayNetClient(NetResult.Success(NetResponse(status, emptyMap(), body.encodeToByteArray())))

        fun failing(error: NetError): ReplayNetClient = ReplayNetClient(NetResult.Failure(error))
    }
}
