package app.sleevesight.sources.lastfm

import app.sleevesight.fixtures.Fixtures
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult

/**
 * A [NetClient] that answers Last.fm calls from the hand-written stubs in `fixtures/http/lastfm/`
 * (see its README): `<method>/<file>.json`, the file chosen by the `artist` parameter through
 * [files]. An artist with no entry fails the test, so no call goes unanswered by accident.
 * [override] answers a call itself, for statuses and failures. Every request is kept in [requests].
 */
internal class LastFmStubClient(
    private val files: Map<String, String> = DEFAULT_FILES,
    private val override: (NetRequest) -> NetResult<NetResponse>? = { null },
) : NetClient {
    val requests = mutableListOf<NetRequest>()

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        requests += request
        override(request)?.let { return it }
        val method = checkNotNull(request.query["method"]) { "no method" }
        val artist = checkNotNull(request.query["artist"]) { "no artist" }
        val file = checkNotNull(files[artist]) { "no stub for $artist" }
        return ok(Fixtures.text("http/lastfm/$method/$file.json"))
    }

    override fun close() = Unit

    companion object {
        /** The stubs by the name asked for. */
        val DEFAULT_FILES: Map<String, String> =
            mapOf(
                "Gordon Lightfoot" to "gordon-lightfoot",
                "Peleboy" to "peleboy",
                "Tony K" to "tony-k",
                "Jessiah" to "jessiah",
                "Reem" to "reem",
                "Nobody Here" to "not-found",
            )

        fun ok(
            body: String,
            status: Int = 200,
        ): NetResult<NetResponse> = NetResult.Success(NetResponse(status, emptyMap(), body.encodeToByteArray()))
    }
}
