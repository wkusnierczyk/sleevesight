package io.github.wkusnierczyk.sleevesight.sources.testing

import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetRequest
import io.github.wkusnierczyk.sleevesight.net.NetResponse
import io.github.wkusnierczyk.sleevesight.net.NetResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A [NetClient] that sends each request through [live] and prints each successful exchange as
 * one line, `HTTP-RECORD {json}`, to standard output, which the test report keeps.
 * `fixtures/http/record.py` turns those lines into the recorded responses [ReplayNetClient] serves
 * (the procedure is in `fixtures/http/README.md`). Printing rather than writing files keeps the
 * test fixtures free of JVM-only file APIs (D2), and the requests recorded are exactly the ones
 * the code under test makes.
 *
 * Keyless sources only: a request carrying credentials, or a URL marked sensitive, is refused, so
 * a key can never reach a recording.
 */
class RecordingNetClient(
    private val live: NetClient,
) : NetClient {
    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        check(request.secrets.query.isEmpty() && request.secrets.headers.isEmpty() && !request.sensitiveUrl) {
            "Only keyless requests are recorded"
        }
        val result = live.send(request)
        if (result is NetResult.Success) {
            val line =
                JsonObject(
                    mapOf(
                        "method" to JsonPrimitive(request.method.name),
                        "url" to JsonPrimitive(request.url),
                        "query" to JsonObject(request.query.mapValues { JsonPrimitive(it.value) }),
                        "status" to JsonPrimitive(result.value.status),
                        "body" to JsonPrimitive(result.value.body),
                    ),
                )
            println("$PREFIX$line")
        }
        return result
    }

    override fun close() = live.close()

    companion object {
        const val PREFIX: String = "HTTP-RECORD "
    }
}
