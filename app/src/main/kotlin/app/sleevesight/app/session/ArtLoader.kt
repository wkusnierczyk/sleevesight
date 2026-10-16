package app.sleevesight.app.session

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

/**
 * Fetches album art given as an `http` or `https` URI. `:app` may not reach the network, so the
 * app graph wires `:sources`' `ArtFetcher` here once it holds the process's one `NetClient` (#51);
 * until then [NONE] fetches nothing, and such art is missing.
 */
fun interface HttpArt {
    /** The image's bytes, or null for no art. */
    suspend fun fetch(uri: String): ByteArray?

    companion object {
        val NONE = HttpArt { null }
    }
}

/**
 * Turns the art sources of a track ([SessionMapping.artSources]) into a bitmap, trying each in
 * turn: the session's bitmap as it is, a `content://` URI through the [resolver] (which works only
 * where the player grants read access: otherwise a `SecurityException`, and the next source), an
 * `http` URI through [http]. Reading and decoding run in [io].
 */
class ArtLoader(
    private val resolver: ContentResolver,
    private val http: HttpArt,
    private val io: CoroutineContext,
) {
    suspend fun load(sources: List<ArtSource>): Bitmap? {
        for (source in sources) {
            val bitmap =
                when (source) {
                    is ArtSource.Image -> source.bitmap
                    is ArtSource.Content -> withContext(io) { readContent(source.uri)?.let(::decode) }
                    is ArtSource.Http -> http.fetch(source.uri)?.let { withContext(io) { decode(it) } }
                }
            if (bitmap != null) return bitmap
            Log.i(TAG, "No art from ${source.kind()}; trying the next source")
        }
        return null
    }

    // A provider is another app's code: anything it throws means no art from it, not a crash.
    @Suppress("TooGenericExceptionCaught")
    private fun readContent(uri: String): ByteArray? =
        try {
            resolver.openInputStream(uri.toUri())?.use(::readBounded)
        } catch (e: IOException) {
            Log.i(TAG, "Art not readable: ${e::class.simpleName}")
            null
        } catch (e: RuntimeException) {
            // `SecurityException` when the player grants no read access, the usual case.
            Log.i(TAG, "Art not readable: ${e::class.simpleName}")
            null
        }

    /** The whole of [stream], or null past [MAX_BYTES]: the size is the provider's choice. */
    private fun readBounded(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
    }

    /** Decodes [bytes], scaled down by powers of two to at most [MAX_SIDE] pixels a side. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun ArtSource.kind(): String =
        when (this) {
            is ArtSource.Image -> "the session's bitmap"
            is ArtSource.Content -> "a content URI"
            is ArtSource.Http -> "a web address"
        }

    private companion object {
        const val TAG = "Sleevesight"

        /** As `ArtFetcher.MAX_BYTES`: far above any cover. */
        const val MAX_BYTES = 4 * 1024 * 1024

        /** Enough for the largest cover the home screen draws on a phone. */
        const val MAX_SIDE = 1024

        const val BUFFER = 16 * 1024
    }
}
