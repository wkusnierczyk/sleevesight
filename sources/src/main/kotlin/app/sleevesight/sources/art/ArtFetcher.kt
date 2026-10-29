package app.sleevesight.sources.art

import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches album art that a player gives only as an `https` URI (`design/architecture.md`, *UI*):
 * `:app` may not reach `:net`, so the session layer asks here. Castbox sends such URIs.
 *
 * A plain `http` URI is refused before any request (#696): the address may identify the album or
 * episode, so it never travels unencrypted. An `https` server's redirect to plain `http` is not
 * followed either: `:net` ends it as `NetError.Protocol` ("redirect to http refused"), with no
 * second request, so the result is `Failed("Protocol")`. The manifest refuses cleartext too
 * (`usesCleartextTraffic="false"`), on Android 8 as well, whose default allows it; these checks make
 * the refusal a plain, final failure rather than a failed connection, reported as offline and retried.
 *
 * The URI is a stranger's choice, so the answer is bounded ([MAX_BYTES]) and kept only when the
 * server says it is an image, or says nothing of its type; the caller decodes it. For the same
 * reason the request is marked `sensitiveUrl`: the path or query may carry a token or name the
 * track, so `:net`'s log shows only the host.
 */
class ArtFetcher(
    private val net: NetClient,
) {
    suspend fun fetch(uri: String): ArtResult {
        if (!isHttps(uri)) return ArtResult.Failed("not https")
        return when (val result = net.send(request(uri))) {
            is NetResult.Failure -> ArtResult.Failed(result.error::class.simpleName ?: "error")
            is NetResult.Success -> read(result.value)
        }
    }

    private fun read(response: NetResponse): ArtResult {
        val type = response.header("Content-Type")?.trim()?.lowercase()
        return when {
            !response.isSuccess -> ArtResult.Failed("status ${response.status}")
            type != null && !type.startsWith("image/") -> ArtResult.Failed("not an image")
            response.bytes.isEmpty() -> ArtResult.Failed("empty")
            else -> ArtResult.Image(response.bytes)
        }
    }

    companion object {
        /** The request for [uri]: bounded, and with its path and query kept out of the log. */
        internal fun request(uri: String): NetRequest =
            NetRequest(uri, timeout = TIMEOUT, maxBytes = MAX_BYTES, sensitiveUrl = true)

        /** Far above any cover a player links to (Spotify's are about 60 KB at 640 pixels). */
        const val MAX_BYTES: Long = 4L * 1024 * 1024

        val TIMEOUT = 15.seconds

        private fun isHttps(uri: String): Boolean = uri.startsWith("https://", ignoreCase = true)
    }
}

/** What [ArtFetcher.fetch] found. */
sealed interface ArtResult {
    /** The image as served, not yet decoded. */
    class Image(
        val bytes: ByteArray,
    ) : ArtResult {
        override fun toString(): String = "Image(${bytes.size} bytes)"
    }

    /** No art: [reason] is a fixed label or a status, never the URI, which may identify the track. */
    data class Failed(
        val reason: String,
    ) : ArtResult
}
