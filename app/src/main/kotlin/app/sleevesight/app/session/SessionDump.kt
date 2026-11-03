package app.sleevesight.app.session

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
 *
 * It never throws into the session's callbacks: a report it cannot describe is logged as such. A
 * long report is logged in numbered parts, as logcat cuts a record of about 4 kB.
 */
internal object SessionDump {
    const val TAG = "SleevesightDump"

    private val enabled: Boolean = BuildConfig.DEBUG
    private var reports = 0

    fun metadata(
        player: String,
        metadata: MediaMetadata?,
    ) = log(player, "metadata") { describe(metadata) }

    fun state(
        player: String,
        state: PlaybackState?,
    ) = log(player, "state") { describe(state) }

    fun queue(
        player: String,
        queue: List<MediaSession.QueueItem>?,
    ) = log(player, "queue") { describe(queue) }

    private fun log(
        player: String,
        kind: String,
        describe: () -> String,
    ) {
        if (!enabled) return
        val text = runCatching(describe).getOrElse { "not readable: $it" }
        val parts = parts(text)
        val report = ++reports
        parts.forEachIndexed { index, part ->
            Log.d(TAG, "$player $kind #$report ${index + 1}/${parts.size} $part")
        }
    }

    /** [text] in parts short enough for one logcat record each. */
    fun parts(text: String): List<String> = text.chunked(PART_LENGTH).ifEmpty { listOf("") }

    /**
     * Every key with its value, sorted: each read by its type, so no getter of another type logs a
     * warning; a bitmap as its size, a rating as itself, a long text cut.
     */
    fun describe(metadata: MediaMetadata?): String =
        metadata?.keySet()?.sorted()?.joinToString(prefix = "{", postfix = "}") { key ->
            val value =
                runCatching {
                    when (key) {
                        in LONG_KEYS -> metadata.getLong(key).toString()
                        in BITMAP_KEYS -> metadata.getBitmap(key)?.let { "bitmap ${it.width}x${it.height}" }
                        in RATING_KEYS -> metadata.getRating(key)?.toString()
                        else -> metadata.getText(key)?.let(::bounded)
                    }
                }.getOrElse { "not readable: ${it.javaClass.simpleName}" }
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
                    "${it.queueId}:${it.description.mediaId}:${it.description.title?.let(::bounded)}"
                }
        } ?: "null"

    /**
     * The actions [PlaybackState.getActions] holds, by name, so a removed skip shows at a glance; any
     * bit with no name here, as its value in hex.
     */
    fun actionNames(actions: Long): List<String> {
        val named = ACTIONS.filter { (bit, _) -> actions and bit != 0L }
        val unknown = ACTIONS.fold(actions) { left, (bit, _) -> left and bit.inv() }
        return named.map { it.second } +
            listOfNotNull(unknown.takeIf { it != 0L }?.let { "0x${java.lang.Long.toHexString(it)}" })
    }

    /** Each extra as its value when it is plain (text, number, flag), else as its type, never parcelled. */
    private fun describe(extras: Bundle?): String =
        runCatching {
            extras?.keySet()?.sortedBy { it.orEmpty() }?.joinToString(prefix = "{", postfix = "}") { key ->
                val value =
                    runCatching {
                        @Suppress("DEPRECATION") // Bundle.get: any value, only to print it.
                        when (val value = extras.get(key)) {
                            null -> "null"
                            is CharSequence -> bounded(value)
                            is Number, is Boolean -> value.toString()
                            else -> value.javaClass.name
                        }
                    }.getOrElse { "not readable: ${it.javaClass.simpleName}" }
                "$key=$value"
            } ?: "null"
        }.getOrElse { "not readable: ${it.javaClass.simpleName}" }

    private const val QUEUE_ITEMS = 5

    // Under logcat's limit of about 4 kB a record, for text of up to three bytes a character.
    private const val PART_LENGTH = 1000

    // The framework's long, bitmap and rating keys, and the long keys only `MediaMetadataCompat`
    // names (`BT_FOLDER_TYPE`, `ADVERTISEMENT`, `DOWNLOAD_STATUS`); every other key is read as text.
    private val LONG_KEYS =
        setOf(
            MediaMetadata.METADATA_KEY_DURATION,
            MediaMetadata.METADATA_KEY_YEAR,
            MediaMetadata.METADATA_KEY_TRACK_NUMBER,
            MediaMetadata.METADATA_KEY_NUM_TRACKS,
            MediaMetadata.METADATA_KEY_DISC_NUMBER,
            "android.media.metadata.BT_FOLDER_TYPE",
            "android.media.metadata.ADVERTISEMENT",
            "android.media.metadata.DOWNLOAD_STATUS",
        )
    private val BITMAP_KEYS =
        setOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
    private val RATING_KEYS = setOf(MediaMetadata.METADATA_KEY_USER_RATING, MediaMetadata.METADATA_KEY_RATING)

    // `PlaybackState.ACTION_SET_PLAYBACK_SPEED`, API 29's, written out for API 26.
    private const val ACTION_SET_PLAYBACK_SPEED = 0x400000L

    // Every action `PlaybackState` names, by its bit.
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
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "playFromMediaId",
            PlaybackState.ACTION_PLAY_FROM_SEARCH to "playFromSearch",
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "queueItem",
            PlaybackState.ACTION_PLAY_FROM_URI to "playFromUri",
            PlaybackState.ACTION_PREPARE to "prepare",
            PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to "prepareFromMediaId",
            PlaybackState.ACTION_PREPARE_FROM_SEARCH to "prepareFromSearch",
            PlaybackState.ACTION_PREPARE_FROM_URI to "prepareFromUri",
            ACTION_SET_PLAYBACK_SPEED to "setPlaybackSpeed",
        )
}

private const val DUMP_TEXT_LENGTH = 120

/** [text] cut to [DUMP_TEXT_LENGTH] before it is copied, on one line. */
private fun bounded(text: CharSequence): String {
    val cut = text.length > DUMP_TEXT_LENGTH
    val kept = (if (cut) text.subSequence(0, DUMP_TEXT_LENGTH) else text).toString().replace('\n', ' ')
    return if (cut) "$kept…" else kept
}
