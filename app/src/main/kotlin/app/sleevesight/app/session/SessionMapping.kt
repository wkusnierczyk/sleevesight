package app.sleevesight.app.session

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Maps what a media session publishes to the session layer's types (`design/architecture.md`,
 * *Session layer*). Pure functions over the framework's value types, so the tests build the
 * inputs directly.
 */
@Suppress("TooManyFunctions") // One mapping per thing a session publishes, and the tests of it.
object SessionMapping {
    /**
     * The track [metadata] describes, or null when it names none: a session with no title (a Sonos
     * session sent only art) has nothing to look up or show.
     *
     * The title is `TITLE`, else `DISPLAY_TITLE`; the artists are `ARTIST` as sent, one string the
     * normalizer splits, and empty when the player sends none (a player that sends only a title
     * still runs the pipeline). Blank values are absent. [queueItemId] is the active queue item, as
     * the repository settles it with the metadata. The year is `YEAR`, else the year `DATE` begins
     * with (`20260320T000000`, `2026-03-20`), absent when neither holds a plausible one by [clock]'s
     * date ([year]).
     */
    fun nowPlaying(
        player: String,
        metadata: MediaMetadata?,
        queueItemId: Long?,
        clock: Clock = Clock.System,
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
                year = year(metadata, clock),
            )
        }
    }

    /**
     * Whether [metadata] reports music: a title ([title]) and an artist (`ARTIST`), blank values
     * absent; the album is not needed. What lists a player under *Players* (#435): a
     * call (a caller as its title, no artist) and most video apps never report it.
     */
    fun reportsMusic(metadata: MediaMetadata?): Boolean =
        title(metadata) != null && metadata?.text(MediaMetadata.METADATA_KEY_ARTIST) != null

    /**
     * The release's year as the player sends it: `YEAR`, else the four digits `DATE` begins with;
     * either only when it is a plausible year, from [FIRST_YEAR] (players send none as 0) to the year
     * after [clock]'s (a release announced for next year), so a tag's 2099 or 9999 is not shown.
     */
    fun year(
        metadata: MediaMetadata,
        clock: Clock = Clock.System,
    ): Int? {
        val dated =
            metadata
                .text(MediaMetadata.METADATA_KEY_DATE)
                ?.take(YEAR_DIGITS)
                ?.takeIf { it.length == YEAR_DIGITS && it.all(Char::isDigit) }
                ?.toLong()
        val thisYear =
            java.time.Instant
                .ofEpochMilli(clock.now().toEpochMilliseconds())
                .atZone(ZoneOffset.UTC)
                .year
        return listOfNotNull(metadata.getLong(MediaMetadata.METADATA_KEY_YEAR), dated)
            .firstOrNull { it in FIRST_YEAR..thisYear + 1L }
            ?.toInt()
    }

    /** The earliest year taken as a release's: earlier ones are a tag's mistake, not a recording's. */
    private const val FIRST_YEAR = 1800L

    private const val YEAR_DIGITS = 4

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
     *
     * Known costs, each rare and off the measured paths:
     * - A podcast player that resumes the next episode at a saved position (Castbox: `PAUSED` at
     *   31m 18s, item 0; `PAUSED` at 10m, item 0; the new title 90 ms later) does not fall back
     *   to the start, so the change is timed at the title, with the new episode's state as the
     *   one before it.
     * - A restart then *previous* within [SessionRepository.SETTLE] on a player that sends the
     *   title first (Spotify: 230 s, item 1; 0, item 1, at 10 000; the new title at 10 200; then
     *   item 2) falls back at the restart, so the change is timed there, with the state before
     *   the restart as the one before it.
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

    /**
     * [state] as the app reads it, by whether its session plays on another device ([remote],
     * `PLAYBACK_TYPE_REMOTE`): a remote session of one of [CONNECT_PLAYERS] reporting `PAUSED` at a
     * speed above 0 is `PLAYING` at that speed; every other state is as reported. Every reader of
     * a followed session's state takes it from here: the status shown, the bar, the choice of
     * session, skip detection and the history.
     *
     * Spotify Connect publishes one session for the device it plays on, always `PAUSED`; only the
     * speed tells playing (1) from paused (0), and the position is reported at each change (#595,
     * from a capture of the system's log). On the phone itself Spotify reports `PLAYING`. Local
     * sessions are read as reported: a local player reports `PAUSED` or `STOPPED` at speed 1 while
     * it is still (Audioteka, `STOPPED` at 1). Other players' remote sessions are read as reported
     * too: only Spotify's was measured, and a cast session paused at speed 1 would otherwise show as
     * playing.
     */
    fun effectiveState(
        player: String,
        state: PlaybackState?,
        remote: Boolean,
    ): PlaybackState? =
        state
            ?.takeIf {
                remote &&
                    player in CONNECT_PLAYERS &&
                    it.state == PlaybackState.STATE_PAUSED &&
                    it.playbackSpeed > 0f
            }?.let {
                PlaybackState
                    .Builder(it)
                    .setState(PlaybackState.STATE_PLAYING, it.position, it.playbackSpeed, it.lastPositionUpdateTime)
                    .build()
            } ?: state

    /** The players whose remote session reads its speed as playing ([effectiveState], #595): Spotify Connect's. */
    val CONNECT_PLAYERS: Set<String> = setOf(Settings.SPOTIFY)

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
