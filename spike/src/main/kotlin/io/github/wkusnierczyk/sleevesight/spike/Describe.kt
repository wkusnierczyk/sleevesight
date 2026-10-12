package io.github.wkusnierczyk.sleevesight.spike

import android.content.ContentResolver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Turns what a media session exposes into JSON for the log, keeping every field it carries. */
object Describe {
    private const val QUEUE_ITEMS_LOGGED = 10

    private val ART_URI_KEYS =
        listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )

    private val BITMAP_KEYS =
        listOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )

    private val STATES =
        mapOf(
            PlaybackState.STATE_NONE to "NONE",
            PlaybackState.STATE_STOPPED to "STOPPED",
            PlaybackState.STATE_PAUSED to "PAUSED",
            PlaybackState.STATE_PLAYING to "PLAYING",
            PlaybackState.STATE_FAST_FORWARDING to "FAST_FORWARDING",
            PlaybackState.STATE_REWINDING to "REWINDING",
            PlaybackState.STATE_BUFFERING to "BUFFERING",
            PlaybackState.STATE_ERROR to "ERROR",
            PlaybackState.STATE_CONNECTING to "CONNECTING",
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS to "SKIPPING_TO_PREVIOUS",
            PlaybackState.STATE_SKIPPING_TO_NEXT to "SKIPPING_TO_NEXT",
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM to "SKIPPING_TO_QUEUE_ITEM",
        )

    private val ACTIONS =
        mapOf(
            PlaybackState.ACTION_STOP to "STOP",
            PlaybackState.ACTION_PAUSE to "PAUSE",
            PlaybackState.ACTION_PLAY to "PLAY",
            PlaybackState.ACTION_REWIND to "REWIND",
            PlaybackState.ACTION_SKIP_TO_PREVIOUS to "SKIP_TO_PREVIOUS",
            PlaybackState.ACTION_SKIP_TO_NEXT to "SKIP_TO_NEXT",
            PlaybackState.ACTION_FAST_FORWARD to "FAST_FORWARD",
            PlaybackState.ACTION_SET_RATING to "SET_RATING",
            PlaybackState.ACTION_SEEK_TO to "SEEK_TO",
            PlaybackState.ACTION_PLAY_PAUSE to "PLAY_PAUSE",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "PLAY_FROM_MEDIA_ID",
            PlaybackState.ACTION_PLAY_FROM_SEARCH to "PLAY_FROM_SEARCH",
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "SKIP_TO_QUEUE_ITEM",
            PlaybackState.ACTION_PLAY_FROM_URI to "PLAY_FROM_URI",
            PlaybackState.ACTION_PREPARE to "PREPARE",
            PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to "PREPARE_FROM_MEDIA_ID",
            PlaybackState.ACTION_PREPARE_FROM_SEARCH to "PREPARE_FROM_SEARCH",
            PlaybackState.ACTION_PREPARE_FROM_URI to "PREPARE_FROM_URI",
        )

    fun stateName(state: Int): String = STATES[state] ?: "UNKNOWN($state)"

    fun actionNames(actions: Long): JSONArray = JSONArray(ACTIONS.filterKeys { actions and it != 0L }.values.toList())

    /** Every key the metadata carries, with its value, or a summary for bitmaps. */
    fun metadata(
        metadata: MediaMetadata?,
        resolver: ContentResolver,
    ): JSONObject {
        if (metadata == null) return JSONObject().put("present", false)
        val values = JSONObject()
        metadata.keySet().sorted().forEach { key ->
            when {
                key in BITMAP_KEYS ->
                    metadata.getBitmap(key)?.let { values.put(key, "bitmap ${it.width}x${it.height}") }
                metadata.getText(key) != null -> values.put(key, metadata.getText(key).toString())
                metadata.getRating(key) != null -> values.put(key, "rating ${metadata.getRating(key)}")
                else -> values.put(key, metadata.getLong(key))
            }
        }
        val art = JSONObject()
        ART_URI_KEYS.forEach { key ->
            metadata.getString(key)?.let { art.put(key, artAccess(Uri.parse(it), resolver)) }
        }
        return JSONObject()
            .put("present", true)
            .put("keys", JSONArray(metadata.keySet().sorted()))
            .put("values", values)
            .put("artUriAccess", art)
            .put("description", metadata.description?.toString())
    }

    /** Whether an art URI can be read by this app: the question behind the Phase 0 art check. */
    private fun artAccess(
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

    fun playbackState(state: PlaybackState?): JSONObject {
        if (state == null) return JSONObject().put("present", false)
        return JSONObject()
            .put("present", true)
            .put("state", stateName(state.state))
            .put("position", state.position)
            .put("lastPositionUpdateTime", state.lastPositionUpdateTime)
            .put("speed", state.playbackSpeed.toDouble())
            .put("bufferedPosition", state.bufferedPosition)
            .put("actions", actionNames(state.actions))
            .put("activeQueueItemId", state.activeQueueItemId)
            .put("customActions", JSONArray(state.customActions.map { it.action }))
            .put("error", state.errorMessage?.toString())
            .put("extras", bundle(state.extras))
    }

    fun queue(items: List<MediaSession.QueueItem>?): JSONObject {
        if (items == null) return JSONObject().put("present", false)
        val first =
            items.take(QUEUE_ITEMS_LOGGED).map { item ->
                JSONObject()
                    .put("queueId", item.queueId)
                    .put("mediaId", item.description.mediaId)
                    .put("title", item.description.title?.toString())
                    .put("subtitle", item.description.subtitle?.toString())
                    .put("mediaUri", item.description.mediaUri?.toString())
            }
        return JSONObject().put("present", true).put("size", items.size).put("first", JSONArray(first))
    }

    fun controller(controller: MediaController): JSONObject =
        JSONObject()
            .put("package", controller.packageName)
            .put("sessionActivity", controller.sessionActivity != null)
            .put("flags", controller.flags)
            .put("ratingType", controller.ratingType)
            .put("extras", bundle(controller.extras))

    @Suppress("DEPRECATION") // Bundle.get is the only way to read values of unknown type.
    fun bundle(extras: Bundle?): JSONObject {
        val json = JSONObject()
        extras?.keySet()?.sorted()?.forEach { key -> json.put(key, extras.get(key)?.toString()) }
        return json
    }
}
