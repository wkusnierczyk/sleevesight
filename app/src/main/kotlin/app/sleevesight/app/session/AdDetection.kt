package app.sleevesight.app.session

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import app.sleevesight.core.settings.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether what a player's session reports is an ad, not a track (#880; `design/design.md`, *Track
 * changes*): the one place that says so. Spotify Free plays its ads through its media session as if
 * they were tracks (a tester's: title `tetesept`, artist `Advertisement • 2 of 3`, 28 s), and an ad
 * so read would be looked up, written about, read aloud, prepared and kept in the history.
 *
 * Only signals that cannot fire on music count:
 * - **The advertisement flag**, `android.media.metadata.ADVERTISEMENT` ([KEY_ADVERTISEMENT], the
 *   key behind `MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT`; the framework's `MediaMetadata`
 *   names no constant for it) set to 1. A standard key, so it counts for every player.
 * - **Spotify's ad URI**: the media id (`METADATA_KEY_MEDIA_ID`) beginning with `spotify:ad:`
 *   ([SPOTIFY_AD]) where a track's begins with `spotify:track:`; only when the metadata names no
 *   media id, the media id of the queue item the playback state names as active. The metadata's own
 *   id decides when it has one: the active item can lag behind a new track (`NextInQueue`), and a
 *   track after an ad must not read as the ad until the item moves. Spotify's own scheme, so it
 *   counts for Spotify alone ([Settings.SPOTIFY]).
 *
 * Not used, as music can show them too: the artist text ("Advertisement", which Spotify translates
 * into the app's language), a missing album, a length of 30 s or less, skipping removed from the
 * playback state's actions. Which signals Spotify really sends is still to be confirmed by a capture
 * of a Spotify Free account playing an ad (`SessionDump`, #897; `fixtures/sessions/ads/`), which may
 * add to these. Other players' ads (YouTube Music, podcasts) are added as they are seen.
 */
object AdDetection {
    /** The metadata key of the advertisement flag: 1 for an ad, 0 or absent otherwise. */
    const val KEY_ADVERTISEMENT: String = "android.media.metadata.ADVERTISEMENT"

    /** How Spotify's URI of an ad begins: `spotify:ad:` and the ad's id. */
    const val SPOTIFY_AD: String = "spotify:ad:"

    /**
     * Whether [player]'s [metadata], with the media id of the active queue item ([activeItemMediaId]),
     * reports an ad: the advertisement flag set to 1, for any player; for Spotify, a `spotify:ad:`
     * URI as the metadata's media id, else, when the metadata names none, as the active item's.
     */
    fun isAd(
        player: String,
        metadata: MediaMetadata?,
        activeItemMediaId: String? = null,
    ): Boolean {
        val own = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
        return metadata?.getLong(KEY_ADVERTISEMENT) == FLAG_SET || isAdId(player, own ?: activeItemMediaId)
    }

    /**
     * Whether [mediaId], a media id [player] sends (its metadata's, or a queue item's), names an ad:
     * for Spotify, a `spotify:ad:` URI. For the next queue item too (`NextInQueue`), which is not
     * prepared when it is an ad.
     */
    fun isAdId(
        player: String,
        mediaId: String?,
    ): Boolean =
        player == Settings.SPOTIFY &&
            mediaId?.trim()?.startsWith(SPOTIFY_AD, ignoreCase = true) == true

    /**
     * The media id of the queue item [state] names as active, from [queue]; null when it names none,
     * or [queue] does not list it.
     */
    fun activeItemMediaId(
        state: PlaybackState?,
        queue: List<MediaSession.QueueItem>?,
    ): String? {
        val item = SessionMapping.activeItem(state) ?: return null
        return queue?.firstOrNull { it.queueId == item }?.description?.mediaId
    }

    private const val FLAG_SET = 1L
}

/**
 * An ad playing in [player]'s session ([AdDetection]), in place of a track: the session layer
 * publishes no track for it ([SessionRepository.nowPlaying] is null), so nothing downstream looks it
 * up, writes about it, reads it aloud, prepares or keeps it, and Home shows its neutral ad state
 * from this. It holds nothing of the ad's own: no title, artist or art, only its [duration], for the
 * playback position under the tile.
 */
data class AdPlaying(
    val player: String,
    val duration: Duration? = null,
) {
    companion object {
        /** The ad [metadata] of [player] reports, with its length when the player gives one. */
        fun of(
            player: String,
            metadata: MediaMetadata?,
        ): AdPlaying =
            AdPlaying(
                player,
                metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }?.milliseconds,
            )
    }
}
