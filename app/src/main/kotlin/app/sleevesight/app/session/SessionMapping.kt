package app.sleevesight.app.session

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import app.sleevesight.core.model.NowPlaying
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Maps what a media session publishes to the session layer's types (`design/architecture.md`,
 * *Session layer*). Pure functions over the framework's value types, so the tests build the
 * inputs directly.
 */
object SessionMapping {
    /**
     * The track [metadata] describes, or null when it names none: a session with no title (a Sonos
     * session sent only art) has nothing to look up or show.
     *
     * The title is `TITLE`, else `DISPLAY_TITLE`; the artists are `ARTIST` as sent, one string the
     * normalizer splits, and empty when the player sends none (a player that sends only a title
     * still runs the pipeline). Blank values are absent. [queueItemId] is the active queue item, as
     * the repository settles it with the metadata.
     */
    fun nowPlaying(
        player: String,
        metadata: MediaMetadata?,
        queueItemId: Long?,
    ): NowPlaying? {
        metadata ?: return null
        return title(metadata)?.let {
            NowPlaying(
                title = it,
                artists = metadata.text(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM),
                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { ms -> ms > 0 }?.milliseconds,
                player = player,
                mediaId = metadata.text(MediaMetadata.METADATA_KEY_MEDIA_ID),
                queueItemId = queueItemId,
                artUri = artUris(metadata).firstOrNull(),
            )
        }
    }

    /** The track's title as [nowPlaying] reads it. */
    fun title(metadata: MediaMetadata?): String? =
        metadata?.let { it.text(MediaMetadata.METADATA_KEY_TITLE) ?: it.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }

    /**
     * Whether [next] falls back to the start of a track (within [AT_START]) from a position
     * [previous] reported past it (more than twice that): a new track's first state, which may name
     * no new queue item (Castbox). A seek to mid-track is not one, nor a state still at the start
     * (Castbox reports `PLAYING` at 0 while it stalls), nor a fall from a position reported only at
     * the start, however long it has played since: its extrapolation cannot tell it from a stall.
     * Unknown positions never fall back.
     */
    fun movedBack(
        previous: PlaybackState?,
        next: PlaybackState?,
    ): Boolean {
        val start = AT_START.inWholeMilliseconds
        val from = previous?.position ?: -1
        val to = next?.position ?: -1
        return from > 2 * start && to in 0..start
    }

    /** How near the start a new track's first state reports: more than a player's jitter, short of any track. */
    private val AT_START = 1.seconds

    /** The active queue item the playback state names; the session's −1 ("no item") is null. */
    fun activeItem(state: PlaybackState?): Long? =
        state?.activeQueueItemId?.takeIf { it != MediaSession.QueueItem.UNKNOWN_ID.toLong() }

    /** [state] in the app's terms; [PlaybackInfo.NONE]'s status when the player reports none. */
    fun playbackInfo(
        player: String,
        state: PlaybackState?,
    ): PlaybackInfo {
        state ?: return PlaybackInfo(player, PlaybackStatus.NONE)
        return PlaybackInfo(
            player = player,
            status = status(state.state),
            position = state.position.takeIf { it >= 0 }?.milliseconds,
            updatedAt = state.lastPositionUpdateTime,
            speed = state.playbackSpeed,
        )
    }

    /** Whether [state] is playing or about to, which makes its player the one shown. */
    fun isActive(state: PlaybackState?): Boolean = state?.state in ACTIVE

    /** The published queue, or null when the player publishes neither a queue nor its title. */
    fun queue(
        items: List<MediaSession.QueueItem>?,
        title: CharSequence?,
    ): SessionQueue? {
        val name = title?.toString()?.takeIf { it.isNotBlank() }
        if (items == null && name == null) return null
        return SessionQueue(
            title = name,
            items =
                items.orEmpty().map {
                    QueueEntry(
                        id = it.queueId,
                        title = it.description.title?.toString(),
                        subtitle = it.description.subtitle?.toString(),
                        mediaId = it.description.mediaId,
                    )
                },
        )
    }

    /**
     * Where the art can come from, best first: the bitmap the player sends (`ALBUM_ART`, else `ART`),
     * then its `content://` URIs, read locally where the player allows it, then its `http` URIs,
     * fetched through `:sources` (`design/architecture.md`, *UI*). So a player that sends a bitmap,
     * as Spotify and YouTube Music do, never causes a request for its art. Other schemes are
     * skipped: another app's files cannot be read.
     */
    fun artSources(metadata: MediaMetadata?): List<ArtSource> {
        metadata ?: return emptyList()
        val bitmap =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val uris = artUris(metadata)
        return listOfNotNull(bitmap?.let(ArtSource::Image)) +
            uris.filter { it.scheme() == "content" }.map(ArtSource::Content) +
            uris.filter { it.scheme() == "http" || it.scheme() == "https" }.map(ArtSource::Http)
    }

    private fun artUris(metadata: MediaMetadata): List<String> =
        listOfNotNull(
            metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata.text(MediaMetadata.METADATA_KEY_ART_URI),
        ).distinct()

    private fun status(state: Int): PlaybackStatus =
        when (state) {
            PlaybackState.STATE_NONE -> PlaybackStatus.NONE
            PlaybackState.STATE_STOPPED -> PlaybackStatus.STOPPED
            PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
            PlaybackState.STATE_PLAYING -> PlaybackStatus.PLAYING
            PlaybackState.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            PlaybackState.STATE_ERROR -> PlaybackStatus.ERROR
            else -> PlaybackStatus.OTHER
        }

    private val ACTIVE =
        setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        )
}

private fun String.scheme(): String = substringBefore("://", missingDelimiterValue = "").lowercase()

private fun MediaMetadata.text(key: String): String? = getString(key)?.trim()?.takeIf { it.isNotEmpty() }

/** One place the art can come from (see [SessionMapping.artSources]). */
sealed interface ArtSource {
    /** The bitmap in the metadata. Compared by content: Spotify sends the same art anew with every update. */
    class Image(
        val bitmap: Bitmap,
    ) : ArtSource {
        override fun equals(other: Any?): Boolean =
            other is Image && (other.bitmap === bitmap || other.bitmap.sameAs(bitmap))

        override fun hashCode(): Int = bitmap.width * HASH_SCALE + bitmap.height

        override fun toString(): String = "Image(${bitmap.width}x${bitmap.height})"

        private companion object {
            const val HASH_SCALE = 31
        }
    }

    data class Content(
        val uri: String,
    ) : ArtSource

    data class Http(
        val uri: String,
    ) : ArtSource
}
