package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.sleevesight.core.model.NowPlaying
import kotlinx.coroutines.delay

// The header symbol's flash of the player's logo (#1440, #1445): when it shows, and for how long.

// A track as the flash tells one from another (#1445): its player and its play, which every report
// of one play shares, else its title; none for no track.
internal fun trackOf(nowPlaying: NowPlaying?): Pair<String, Any>? =
    nowPlaying?.let { it.player to (it.play ?: it.title) }

/**
 * The player whose own icon the symbol shows now, or null for the note (#1440, #1445): [player], for
 * [FLASH_MS] once it starts playing ([playing]; null, a passing state, keeps what was before), Home
 * switches to it from another player, or its track changes ([track], its player's; a skip or the next
 * track on its own, playing or not), never on the first look at Home, and only while *Show the current
 * player's logo* is on. Neither *Spin the logo* nor the phone's animations stop it (#1445).
 * Noticing the change and timing the flash are apart, so a pause or another report within it does not
 * cut it short, and a change within it starts it afresh; it ends early only when Home leaves, the
 * player goes, or the setting is switched off, and switching it back on starts none. No timer of its
 * own besides.
 */
@Composable
internal fun flashesIcon(
    feed: HeaderPlayerFeed,
    player: String?,
    playing: Boolean?,
    track: Pair<String, Any>?,
): String? {
    val settings by feed.settings.collectAsState()
    val shows = settings?.showPlayerLogo == true
    val showing by rememberUpdatedState(shows)
    // The player whose icon shows, and each flash's own number, which its timer goes by.
    var flashing by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableIntStateOf(0) }
    // What the last look saw: the player, whether it played, and the player's last track; none before the first.
    val seen = remember { arrayOfNulls<Seen>(1) }
    // A track of another player than the one shown is not yet its own: it is looked at once the player is.
    val own = track?.takeIf { it.first == player }
    LaunchedEffect(player, playing, own) {
        val before = seen[0]
        val plays = playing ?: before?.playing ?: false
        // No track (idle, an ad) keeps the last one, so the same play back again is no change.
        seen[0] = Seen(player, plays, own ?: before?.takeIf { it.player == player }?.track)
        if (player != flashing) flashing = null
        if (showing && before != null && flashes(before, player, plays, own)) {
            flashing = player
            flash++
        }
    }
    LaunchedEffect(flash) {
        if (flashing == null) return@LaunchedEffect
        delay(FLASH_MS)
        flashing = null
    }
    if (!shows && flashing != null) SideEffect { flashing = null }
    return flashing.takeIf { shows && it == player }
}

// What the flash last saw (#1445): the [player] shown, whether it [playing], and its last [track].
private class Seen(
    val player: String?,
    val playing: Boolean,
    val track: Pair<String, Any>?,
)

// Whether a player [player], [playing] or not, with [track] (its own, or none), after [before]
// calls for its icon: another player shown in place of one, a player that starts playing, or another
// track of the same player than the one it last had (#1445).
private fun flashes(
    before: Seen,
    player: String?,
    playing: Boolean,
    track: Pair<String, Any>?,
): Boolean {
    if (player == null) return false
    val was = before.player
    val switched = was != null && player != was
    val started = playing && !(player == was && before.playing)
    val changed = player == was && track != null && before.track != null && track != before.track
    return switched || started || changed
}

/** How long the player's icon shows in the symbol's place (#1445: 3 s, from 1 s). */
internal const val FLASH_MS = 3_000L
