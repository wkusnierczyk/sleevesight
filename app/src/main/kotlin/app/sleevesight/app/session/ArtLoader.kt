package app.sleevesight.app.session

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches album art given as an `https` URI. `:app` may not reach the network, so the
 * app graph points this at the art fetch `:wiring` builds on the process's one `NetClient`
 * (`PipelineWiring.art`, over `:sources`' `ArtFetcher`) once the pipeline runs on the phone (#51);
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
 * This fetch, waiting first until [allowed] is true: in the app, while lookups are allowed (D6). A
 * fetch for a track no longer shown is cancelled with its load, so on return only the current
 * track's art is fetched. [allowed] is read at each fetch.
 */
fun HttpArt.onlyWhen(allowed: () -> Flow<Boolean>): HttpArt =
    HttpArt { uri ->
        allowed().first { it }
        fetch(uri)
    }

/**
 * Turns the art sources of a track ([SessionMapping.artSources]) into a bitmap, trying each in
 * turn: the session's bitmap as it is, a `content://` URI through the [resolver] (which works only
 * where the player grants read access: otherwise a `SecurityException`, and the next source), an
 * `https` URI through [http]. Decoding runs in [io].
 *
 * A `content://` read runs on a thread of [reads], not in [io]: the provider is another app's
 * code, and a provider that never answers would hold a thread that coroutine cancellation cannot
 * free. The read is given [CONTENT_TIMEOUT]; then, or when the load is cancelled, the open is
 * cancelled through its `CancellationSignal` and the stream closed, which frees the thread where
 * the provider honours either, and the next source is tried (#1042). [reads] bounds the reads in
 * flight and passes over a provider whose read is still stuck: then the URI is skipped like an
 * unreadable one.
 */
class ArtLoader(
    private val resolver: ContentResolver,
    private val http: HttpArt,
    private val io: CoroutineContext,
    private val reads: ContentReads = ContentReads.SHARED,
) {
    suspend fun load(sources: List<ArtSource>): Bitmap? {
        for (source in sources) {
            val bitmap =
                when (source) {
                    is ArtSource.Image -> source.bitmap
                    is ArtSource.Content -> readContentInTime(source.uri)?.let { withContext(io) { decode(it) } }
                    is ArtSource.Http -> http.fetch(source.uri)?.let { withContext(io) { decode(it) } }
                }
            if (bitmap != null) return bitmap
            Log.i(TAG, "No art from ${source.kind()}; trying the next source")
        }
        return null
    }

    /** [uri]'s bytes, read on [reads] within [CONTENT_TIMEOUT], or null. */
    private suspend fun readContentInTime(uri: String): ByteArray? {
        val authority = uri.toUri().authority.orEmpty()
        if (!reads.tryStart(authority)) {
            Log.i(TAG, "Art not read: too many reads in flight, or the provider has not answered yet")
            return null
        }
        return readStarted(uri, authority)
    }

    /** [readContentInTime] once [reads] has given [authority]'s read a slot, which this frees. */
    private suspend fun readStarted(
        uri: String,
        authority: String,
    ): ByteArray? {
        val signal = CancellationSignal()
        val opened = AtomicReference<Closeable?>()
        val read = CompletableDeferred<ByteArray?>()
        val state = AtomicInteger(RUNNING)
        val started =
            try {
                reads.executor.execute {
                    try {
                        read.complete(readContent(uri, signal, opened))
                    } finally {
                        read.complete(null)
                        if (!state.compareAndSet(RUNNING, RETURNED)) reads.unmarkStuck(authority)
                        reads.finish()
                    }
                }
                true
            } catch (e: RejectedExecutionException) {
                Log.i(TAG, "Art not read: ${e::class.simpleName}")
                reads.finish()
                false
            }
        if (!started) return null
        try {
            return withTimeoutOrNull(CONTENT_TIMEOUT) { read.await() }
                .also {
                    if (!read.isCompleted) {
                        Log.i(TAG, "Art not read in time")
                        // Marked first, so the reader, once it sees STUCK, has a mark to remove;
                        // unmarked again if the read returned in between.
                        reads.markStuck(authority)
                        if (!state.compareAndSet(RUNNING, STUCK)) reads.unmarkStuck(authority)
                    }
                }
        } finally {
            if (!read.isCompleted) {
                // Cancel before reading [opened]: the reader stores the stream before it checks the
                // signal, so one of the two sides closes it.
                signal.cancel()
                closeQuietly(opened.get())
            }
        }
    }

    // A provider is another app's code: anything it throws means no art from it, not a crash.
    @Suppress("TooGenericExceptionCaught")
    private fun readContent(
        uri: String,
        signal: CancellationSignal,
        opened: AtomicReference<Closeable?>,
    ): ByteArray? =
        try {
            resolver.openAssetFileDescriptor(uri.toUri(), "r", signal)?.use { file ->
                val stream = file.createInputStream()
                opened.set(stream)
                if (signal.isCanceled) null else stream.use(::readBounded)
            }
        } catch (e: IOException) {
            Log.i(TAG, "Art not readable: ${e::class.simpleName}")
            null
        } catch (e: RuntimeException) {
            // `SecurityException` when the player grants no read access, the usual case.
            Log.i(TAG, "Art not readable: ${e::class.simpleName}")
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: IOException) {
            Log.i(TAG, "Art stream not closed: ${e::class.simpleName}")
        } catch (e: RuntimeException) {
            Log.i(TAG, "Art stream not closed: ${e::class.simpleName}")
        }
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

        /** As `ArtFetcher.TIMEOUT`, the bound on the other way art is read: far above a cover's read. */
        val CONTENT_TIMEOUT = 15.seconds

        // A read's [AtomicInteger] state: whichever of the reader and the timeout moves it first.
        const val RUNNING = 0
        const val RETURNED = 1
        const val STUCK = 2
    }
}
