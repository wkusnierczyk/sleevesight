package io.github.wkusnierczyk.sleevesight.spike

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Tries to read each album-art URI a player publishes, once per URI and off the main thread,
 * and logs whether this app can: the Phase 0 question of whether a `content://` grant holds.
 */
object ArtProbe {
    private val reader = Executors.newSingleThreadExecutor()
    private val checked = mutableSetOf<String>()

    fun check(
        uri: String,
        player: String,
        resolver: ContentResolver,
    ) {
        if (!synchronized(checked) { checked.add(uri) }) return
        reader.execute { ProbeLog.event("art_access", read(Uri.parse(uri), resolver).put("package", player)) }
    }

    private fun read(
        uri: Uri,
        resolver: ContentResolver,
    ): JSONObject {
        val result = JSONObject().put("uri", uri.toString()).put("scheme", uri.scheme)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT && uri.scheme != ContentResolver.SCHEME_ANDROID_RESOURCE) {
            return result.put("read", "not attempted: not a content URI")
        }
        // Any failure is the measurement itself, so it is recorded rather than rethrown.
        @Suppress("TooGenericExceptionCaught")
        return try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes().size }
            result.put("read", if (bytes != null) "ok" else "null stream").put("bytes", bytes)
        } catch (e: Exception) {
            result.put("read", "failed").put("error", e.javaClass.name).put("message", e.message)
        }
    }
}
