package io.github.wkusnierczyk.sleevesight.sources.testing

import io.github.wkusnierczyk.sleevesight.fixtures.Fixtures
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetMethod
import io.github.wkusnierczyk.sleevesight.net.NetRequest
import io.github.wkusnierczyk.sleevesight.net.NetResponse
import io.github.wkusnierczyk.sleevesight.net.NetResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A [NetClient] that answers from recorded responses (`fixtures/http/<source>/`), for adapter tests
 * that never reach the network. Each source directory has an `index.json` listing its recorded
 * requests ([RecordedRequest]); a request matches an entry by method, URL and query, exactly.
 *
 * A request with no recording fails the test at once ([AssertionError], which no adapter catches
 * as a parse error), naming it, so a missing recording is never mistaken for a source's silence.
 * [sent] lists the requests made, in order, for tests of what an adapter asks.
 *
 * In `:sources`' test fixtures, so every adapter's tests and `:cli`'s can use it:
 * `testImplementation(testFixtures(project(":sources")))`.
 */
class ReplayNetClient(
    vararg sources: String,
) : NetClient {
    private val recordings: Map<Key, Recorded> =
        sources
            .flatMap { source ->
                val index = Fixtures.text("$DIRECTORY/$source/index.json")
                json.decodeFromString<List<RecordedRequest>>(index).map { entry ->
                    entry.key() to Recorded(entry.status, "$DIRECTORY/$source/${entry.file}")
                }
            }.toMap()

    private val log = mutableListOf<NetRequest>()

    val sent: List<NetRequest> get() = log.toList()

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        log += request
        val key = Key(request.method.name, request.url, request.query)
        val recorded = recordings[key] ?: throw AssertionError("No recorded response for ${describe(key)}")
        val body = Fixtures.text(recorded.path).encodeToByteArray()
        return NetResult.Success(
            NetResponse(recorded.status, mapOf("content-type" to listOf("application/json")), body),
        )
    }

    override fun close() = Unit

    private data class Key(
        val method: String,
        val url: String,
        val query: Map<String, String>,
    )

    private class Recorded(
        val status: Int,
        val path: String,
    )

    private fun RecordedRequest.key(): Key = Key(method, url, query)

    private fun describe(key: Key): String {
        val query =
            key.query.entries
                .sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" }
        return "${key.method} ${key.url} $query"
    }

    companion object {
        /** The recorded responses, under `fixtures/`. */
        const val DIRECTORY: String = "http"

        private val json = Json { ignoreUnknownKeys = false }
    }
}

/** One entry of a recording index: the request, and the status and file of its response. */
@Serializable
data class RecordedRequest(
    val method: String = NetMethod.GET.name,
    val url: String,
    val query: Map<String, String> = emptyMap(),
    val status: Int,
    val file: String,
)
