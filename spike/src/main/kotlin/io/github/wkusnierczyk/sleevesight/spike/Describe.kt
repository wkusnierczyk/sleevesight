package io.github.wkusnierczyk.sleevesight.spike

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
    fun metadata(metadata: MediaMetadata?): JSONObject {
        if (metadata == null) return JSONObject().put("present", false)
        val values = JSONObject()
        metadata.keySet().sorted().forEach { key -> values.put(key, value(metadata, key)) }
        return JSONObject()
            .put("present", true)
            .put("keys", JSONArray(metadata.keySet().sorted()))
            .put("values", values)
            .put("artUris", JSONArray(artUris(metadata)))
            .put("description", metadata.description?.toString())
    }

    /** The art URIs the metadata carries, for [ArtProbe]. */
    fun artUris(metadata: MediaMetadata?): List<String> = ART_URI_KEYS.mapNotNull { metadata?.getString(it) }

    /**
     * A value of unknown type. `MediaMetadata` has no typed lookup, and a getter of the wrong type
     * returns its default (logging a warning to logcat), so the types are tried in turn.
     */
    private fun value(
        metadata: MediaMetadata,
        key: String,
    ): Any =
        metadata.getBitmap(key)?.let { "bitmap ${it.width}x${it.height}" }
            ?: metadata.getText(key)?.toString()
            ?: metadata.getRating(key)?.let { "rating $it" }
            ?: JSONObject().put("longOrUnknown", metadata.getLong(key))

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

    /**
     * A bundle's contents. Unparcelling a player's bundle can throw (for example a
     * `BadParcelableException` for a class this app does not have), so the failure is logged
     * instead of crashing the probe inside a callback.
     */
    @Suppress("DEPRECATION", "TooGenericExceptionCaught") // Bundle.get reads values of unknown type.
    fun bundle(extras: Bundle?): JSONObject {
        val json = JSONObject()
        try {
            extras?.keySet()?.sorted()?.forEach { key -> json.put(key, extras.get(key)?.toString()) }
        } catch (e: Exception) {
            json.put("unreadable", e.javaClass.name).put("message", e.message)
        }
        return json
    }
}
