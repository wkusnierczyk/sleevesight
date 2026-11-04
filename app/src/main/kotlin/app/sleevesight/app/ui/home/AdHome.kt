package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.session.AdPlaying
import app.sleevesight.app.session.PlaybackInfo

/**
 * Home while the player plays an ad (#880; the session layer's [AdPlaying]), in place of a track: the
 * player row, as for a track; in the art's place a plain tile, with nothing of the ad's (no image,
 * no text the player sends), and the playback position under it when the player gives the ad's
 * length; "Ad playing on <player>", the player named as the player row names it; under it, that the
 * next track shows when the ad ends; then, where *Key insights* would be, a note that nothing is
 * looked up or written for ads.
 *
 * Nothing else: no *Dates*, *Genres* or *Lyrics*, no *Sources*, no song, artist or album rows, no
 * *Ask AI*, *Write insight* or *Read aloud*. This is a deliberate exception to the rule that *Dates*,
 * *Genres* and *Lyrics* always show: an ad has nothing to show in them, and with no button on screen
 * no tap can start a lookup or a paid call for it. The track after the ad shows as usual. The header
 * logo's record stands still meanwhile: it turns while music plays, and an ad is no track.
 */
@Composable
internal fun AdHome(
    ad: AdPlaying,
    playback: PlaybackInfo,
    actions: NowPlayingActions,
) {
    val name = remember(ad.player) { actions.playerLabel(ad.player) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AD_HOME_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlayerRow(ad.player, playback, actions)
        // No art: the plain tile a track without art shows, never the ad's image.
        ArtAndProgress(art = null, playback = playback, duration = ad.duration)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.home_ad_title, name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.home_ad_next),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // As a panel's ground (`Panel`), with no heading and nothing to close.
        val shape = RoundedCornerShape(12.dp)
        Text(
            stringResource(R.string.home_ad_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = NOTE_FILL), shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * Home with no track to show: an ad playing in its place ([AdHome], #880), first, as the session
 * layer sets it before it publishes no track for it and clears it after the track that follows;
 * else nothing playing ([IdleHome], #627).
 */
@Composable
internal fun NoTrackHome(
    state: NowPlayingState,
    actions: NowPlayingActions,
) {
    val ad = state.ad
    if (ad != null) {
        AdHome(ad, state.playback, actions)
    } else {
        IdleHome(state.settings, state.guideHidden, actions)
    }
}

/** The test tag of Home's ad state. */
const val AD_HOME_TAG: String = "ad-home"

// A panel's fill (`Panel`).
private const val NOTE_FILL = 0.05f
