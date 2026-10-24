package app.sleevesight.app.session

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import org.robolectric.shadows.PlaybackInfoBuilder

/** Builders and fakes for the session-layer tests. */
internal object Sessions {
    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val VIDEO_APP = "com.example.video"
    const val TELECOM = "com.android.server.telecom"

    @Suppress("LongParameterList") // One parameter per metadata key the tests set.
    fun metadata(
        title: String? = "Sundown",
        artist: String? = "Gordon Lightfoot",
        album: String? = null,
        durationMs: Long? = null,
        mediaId: String? = null,
        albumArt: Bitmap? = null,
        art: Bitmap? = null,
        albumArtUri: String? = null,
        artUri: String? = null,
        displayTitle: String? = null,
    ): MediaMetadata =
        MediaMetadata
            .Builder()
            .apply {
                title?.let { putString(MediaMetadata.METADATA_KEY_TITLE, it) }
                displayTitle?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it) }
                artist?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
                album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
                durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
                mediaId?.let { putString(MediaMetadata.METADATA_KEY_MEDIA_ID, it) }
                albumArt?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
                albumArtUri?.let { putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it) }
                artUri?.let { putString(MediaMetadata.METADATA_KEY_ART_URI, it) }
            }.build()

    fun state(
        state: Int = PlaybackState.STATE_PLAYING,
        positionMs: Long = 0,
        speed: Float = 1f,
        updatedAt: Long = 0,
        queueItem: Long = MediaSession.QueueItem.UNKNOWN_ID.toLong(),
    ): PlaybackState =
        PlaybackState
            .Builder()
            .setState(state, positionMs, speed, updatedAt)
            .setActiveQueueItemId(queueItem)
            .build()

    fun queueItem(
        id: Long,
        title: String,
        subtitle: String? = null,
        mediaId: String? = null,
    ): MediaSession.QueueItem =
        MediaSession.QueueItem(
            MediaDescription
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setMediaId(mediaId)
                .build(),
            id,
        )

    fun bitmap(
        color: Int,
        size: Int = 4,
    ): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}

/** A player's session whose changes the test makes, calling the registered callbacks as the system would. */
@Suppress("LongParameterList") // One parameter per thing a session publishes.
internal class FakeSession(
    override val player: String,
    metadata: MediaMetadata? = Sessions.metadata(),
    playbackState: PlaybackState? = Sessions.state(),
    queue: List<MediaSession.QueueItem>? = null,
    queueTitle: CharSequence? = null,
    sessionActivity: android.app.PendingIntent? = null,
    override var marksCall: Boolean = false,
) : PlayerSession {
    override val key: Any = Any()
    val callbacks = mutableListOf<MediaController.Callback>()

    /** How often the session layer has read [metadata]. */
    var metadataReads = 0
        private set

    /** How often the session layer has read anything the player reports: metadata, state, queue, its screen. */
    var reads = 0
        private set

    override var metadata: MediaMetadata? = metadata
        get() {
            metadataReads++
            reads++
            onMetadataRead()
            return field
        }
    override var playbackState: PlaybackState? = playbackState
        get() = field.also { reads++ }
    override var queue: List<MediaSession.QueueItem>? = queue
        get() = field.also { reads++ }
    override var queueTitle: CharSequence? = queueTitle
        get() = field.also { reads++ }
    override var sessionActivity: android.app.PendingIntent? = sessionActivity
        get() = field.also { reads++ }

    /** Runs as [metadata] is read, before its value is returned: a change the player makes at that moment. */
    var onMetadataRead: () -> Unit = {}

    /** Runs as a callback is registered: a change the player makes at that moment, before any is heard. */
    var onRegister: () -> Unit = {}

    override fun register(callback: MediaController.Callback) {
        onRegister()
        callbacks += callback
    }

    override fun unregister(callback: MediaController.Callback) {
        callbacks -= callback
    }

    fun send(metadata: MediaMetadata) {
        this.metadata = metadata
        callbacks.toList().forEach { it.onMetadataChanged(metadata) }
    }

    fun send(state: PlaybackState) {
        playbackState = state
        callbacks.toList().forEach { it.onPlaybackStateChanged(state) }
    }

    fun sendQueue(
        items: List<MediaSession.QueueItem>,
        title: String,
    ) {
        queue = items
        queueTitle = title
        callbacks.toList().forEach {
            it.onQueueChanged(items)
            it.onQueueTitleChanged(title)
        }
    }

    fun sendQueueItems(items: List<MediaSession.QueueItem>) {
        queue = items
        callbacks.toList().forEach { it.onQueueChanged(items) }
    }

    fun sendQueueTitle(title: String) {
        queueTitle = title
        callbacks.toList().forEach { it.onQueueTitleChanged(title) }
    }

    /** Turns the session into a call, or back ([marksCall]), as its audio attributes would. */
    fun call(on: Boolean) {
        marksCall = on
        val usage = if (on) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val info =
            PlaybackInfoBuilder
                .newBuilder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
                .build()
        callbacks.toList().forEach { it.onAudioInfoChanged(info) }
    }

    fun destroy() = callbacks.toList().forEach { it.onSessionDestroyed() }
}

/** The system's active sessions, counting every read, so a test can show that nothing was read. */
internal class FakeActiveSessions(
    var active: List<PlayerSession> = emptyList(),
) : ActiveSessions {
    var reads = 0
        private set
    var listener: ((List<PlayerSession>) -> Unit)? = null
        private set

    override fun current(): List<PlayerSession> {
        reads++
        return active
    }

    override fun listen(onChange: (List<PlayerSession>) -> Unit) {
        listener = onChange
    }

    override fun stop() {
        listener = null
    }

    fun change(active: List<PlayerSession>) {
        this.active = active
        listener?.invoke(active)
    }
}
