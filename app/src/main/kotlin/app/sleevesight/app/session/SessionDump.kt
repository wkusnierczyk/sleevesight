package app.sleevesight.app.session

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log
import app.sleevesight.app.BuildConfig

/**
 * Debug builds only: logs everything a followed player reports, each key of its metadata with its
 * value, the playback state's actions and active item, and the queue's item ids, under [TAG]. For
 * the capture that picks how an ad is told from a track (#880): `dumpsys media_session` prints only
 * a metadata's description, not its keys (`METADATA_KEY_ADVERTISEMENT`) or the media id. Read it
 * with `adb logcat -s SleevesightDump`. A release build logs nothing of this.
 */
internal object SessionDump {
    const val TAG = "SleevesightDump"

    private val enabled: Boolean = BuildConfig.DEBUG

    fun metadata(
        player: String,
        metadata: MediaMetadata?,
    ) {
        if (enabled) Log.d(TAG, "$player metadata ${describe(metadata)}")
    }

    fun state(
        player: String,
        state: PlaybackState?,
    ) {
        if (enabled) Log.d(TAG, "$player state ${describe(state)}")
    }

    fun queue(
        player: String,
        queue: List<MediaSession.QueueItem>?,
    ) {
        if (enabled) Log.d(TAG, "$player queue ${describe(queue)}")
    }

    /** Every key with its value, sorted; a bitmap as its size, a long text cut. */
    fun describe(metadata: MediaMetadata?): String =
        metadata?.keySet()?.sorted()?.joinToString(prefix = "{", postfix = "}") { key ->
            val value =
                when (val bitmap = metadata.getBitmap(key)) {
                    null -> readable(metadata.getText(key) ?: metadata.getLong(key))
                    else -> bitmapSize(bitmap)
                }
            "$key=$value"
        } ?: "null"

    fun describe(state: PlaybackState?): String =
        state?.let {
            "{state=${it.state} position=${it.position} speed=${it.playbackSpeed} " +
                "actions=${actionNames(it.actions)} activeItem=${it.activeQueueItemId} " +
                "custom=${it.customActions.map { action -> action.action }} extras=${describe(it.extras)}}"
        } ?: "null"

    fun describe(queue: List<MediaSession.QueueItem>?): String =
        queue?.let { items ->
            "size=${items.size} " +
                items.take(QUEUE_ITEMS).joinToString(prefix = "[", postfix = "]") {
                    "${it.queueId}:${it.description.mediaId}:${readable(it.description.title)}"
                }
        } ?: "null"

    /** The actions [PlaybackState.getActions] holds, by name, so a removed skip shows at a glance. */
    fun actionNames(actions: Long): List<String> =
        ACTIONS.filter { (bit, _) -> actions and bit != 0L }.map { it.second }

    private fun describe(extras: Bundle?): String =
        extras?.keySet()?.sorted()?.joinToString(prefix = "{", postfix = "}") {
            @Suppress("DEPRECATION") // Bundle.get: any value, only to print it.
            "$it=${readable(extras.get(it))}"
        } ?: "null"

    private fun bitmapSize(bitmap: Bitmap) = "bitmap ${bitmap.width}x${bitmap.height}"

    private fun readable(value: Any?): String = value.toString().replace('\n', ' ').take(TEXT_LENGTH)

    private const val QUEUE_ITEMS = 5
    private const val TEXT_LENGTH = 120

    private val ACTIONS =
        listOf(
            PlaybackState.ACTION_STOP to "stop",
            PlaybackState.ACTION_PAUSE to "pause",
            PlaybackState.ACTION_PLAY to "play",
            PlaybackState.ACTION_REWIND to "rewind",
            PlaybackState.ACTION_SKIP_TO_PREVIOUS to "previous",
            PlaybackState.ACTION_SKIP_TO_NEXT to "next",
            PlaybackState.ACTION_FAST_FORWARD to "fastForward",
            PlaybackState.ACTION_SET_RATING to "rating",
            PlaybackState.ACTION_SEEK_TO to "seek",
            PlaybackState.ACTION_PLAY_PAUSE to "playPause",
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "queueItem",
        )
}
