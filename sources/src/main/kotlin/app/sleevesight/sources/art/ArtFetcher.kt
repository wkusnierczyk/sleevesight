package app.sleevesight.sources.art

import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches album art that a player gives only as an `http` or `https` URI (`design/architecture.md`,
 * *UI*): `:app` may not reach `:net`, so the session layer asks here. Castbox sends such URIs.
 *
 * The URI is a stranger's choice, so the answer is bounded ([MAX_BYTES]) and kept only when the
 * server says it is an image, or says nothing of its type; the caller decodes it.
 */
class ArtFetcher(
    private val net: NetClient,
) {
    suspend fun fetch(uri: String): ArtResult {
        if (!isHttp(uri)) return ArtResult.Failed("not http")
        return when (val result = net.send(NetRequest(uri, timeout = TIMEOUT, maxBytes = MAX_BYTES))) {
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
        /** Far above any cover a player links to (Spotify's are about 60 KB at 640 pixels). */
        const val MAX_BYTES: Long = 4L * 1024 * 1024

        val TIMEOUT = 15.seconds

        private fun isHttp(uri: String): Boolean {
            val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
            return (scheme == "http" || scheme == "https") && uri.startsWith("$scheme://", ignoreCase = true)
        }
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
