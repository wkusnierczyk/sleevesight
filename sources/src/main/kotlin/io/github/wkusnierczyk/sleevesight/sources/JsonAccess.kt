package io.github.wkusnierczyk.sleevesight.sources

import io.github.wkusnierczyk.sleevesight.core.resolution.Lookup
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetError
import io.github.wkusnierczyk.sleevesight.net.NetRequest
import io.github.wkusnierczyk.sleevesight.net.NetResponse
import io.github.wkusnierczyk.sleevesight.net.NetResult
import io.github.wkusnierczyk.sleevesight.net.fetch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Reading the parts of a JSON answer an adapter uses, tolerating everything else: sources add
// fields, and a field an adapter does not read must not fail it.

internal val lenientJson: Json = Json { ignoreUnknownKeys = true }

internal fun parseObject(text: String): JsonObject = lenientJson.parseToJsonElement(text) as JsonObject

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

internal fun JsonElement?.array(key: String): List<JsonElement> =
    ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()

internal fun JsonElement?.string(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull

internal fun JsonElement?.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

/**
 * Sends [request] and parses a successful answer with [parse]: a 404 is [Lookup.NotFound], any
 * other unsuccessful status or a network error [Lookup.Failed], naming only its kind.
 */
@Suppress("RedundantSuspendModifier") // Detekt 1.23.8 cannot resolve `fetch`, a suspending call into `:net`.
internal suspend fun <T> NetClient.lookup(
    request: NetRequest,
    parse: (NetResponse) -> T,
): Lookup<T> {
    val result =
        fetch(request) { response ->
            when {
                response.isSuccess -> Answer.Value(parse(response))
                response.status == NOT_FOUND -> Answer.Missing
                else -> Answer.Status(response.status)
            }
        }
    return when (result) {
        is NetResult.Failure -> Lookup.Failed(describe(result.error))
        is NetResult.Success ->
            when (val answer = result.value) {
                is Answer.Value -> Lookup.Found(answer.value)
                is Answer.Missing -> Lookup.NotFound
                is Answer.Status -> Lookup.Failed("status ${answer.status}")
            }
    }
}

private const val NOT_FOUND = 404

private sealed interface Answer<out T> {
    data class Value<out T>(
        val value: T,
    ) : Answer<T>

    data object Missing : Answer<Nothing>

    data class Status(
        val status: Int,
    ) : Answer<Nothing>
}

// The kind of error only: never a URL or a value (architecture, Networking).
private fun describe(error: NetError): String = error::class.simpleName ?: "error"
