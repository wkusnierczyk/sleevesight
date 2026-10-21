package app.sleevesight.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * How every provider's client reads its service's JSON: fields it does not know are ignored, so
 * new ones do not break it.
 */
internal val wireJson = Json { ignoreUnknownKeys = true }

/** [text] decoded as [T], or null if it is not one; why is of no use to the caller. */
@Suppress("SwallowedException")
internal inline fun <reified T> decode(text: String): T? =
    try {
        wireJson.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
