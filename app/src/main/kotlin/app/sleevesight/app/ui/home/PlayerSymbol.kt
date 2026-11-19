package app.sleevesight.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.logo.animatorDurationScale
import app.sleevesight.app.ui.settings.PlayerApps
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.delay

// The player symbol in Home's header (#1440): a neutral music note, in place of the followed
// player's own icon (#1209), which looked foreign among the app's own marks.

/** What the header's player symbol stands for (#1440). */
private enum class SymbolState {
    /** A followed player has a session: the symbol opens the player menu. */
    PLAYER,

    /** Players are switched on in *Settings → Players*, but none has a session: dimmed. */
    NOTHING_PLAYING,

    /** No player is switched on: dimmed. */
    NONE_ENABLED,
}

/**
 * The player symbol in Home's header (#1440): the music note alone, 48 dp, in the header buttons'
 * colour, shown always. With a followed player's session ([SymbolState.PLAYER]), a tap opens the
 * player menu under it ([PlayerMenu]), or closes it, and a long press opens the player, as the menu's
 * *Open <player>* does. With players switched on but none playing, it is dimmed: a tap opens a panel
 * that says nothing is playing, and a long press opens the last player followed
 * ([HeaderPlayerFeed.lastPlayer]), else the first one switched on. With no player switched on, it
 * is dimmed too, and a tap or a long press opens a panel that says so and links to *Settings →
 * Players* ([onOpenPlayers]). A panel's *Dismiss*, a tap outside or Back closes it; the next tap
 * opens it again.
 *
 * Once, for a second, when a followed player starts playing or Home switches to another player, the
 * symbol shows that player's own icon ([PlayerIcon]) in place of the note ([flashesIcon]); not with
 * the phone's animations off, nor while *Spin the logo* holds the record still.
 *
 * A screen reader reads it as *Player menu*, a button, with what a tap and a long press do, and,
 * dimmed, *No player enabled* or *Nothing playing*.
 */
@Composable
internal fun PlayerSymbol(
    feed: HeaderPlayerFeed,
    onOpenPlayers: () -> Unit,
) {
    val playback by feed.playback.collectAsState()
    val settings by feed.settings.collectAsState()
    val player = playback.player
    val apps = remember(feed) { PlayerApps(feed.label, feed.launch, feed.installed) }
    val state = symbolState(player, settings)
    // The player last followed, kept while Home is away too, for a long press while none plays.
    val last by feed.lastPlayer.collectAsState()
    val firstOn = settings?.let { menuPlayers(it, apps) }?.firstOrNull()
    // What is open: the menu, or a panel, as the state was at the tap; none when the state moves on.
    var opened by rememberSaveable { mutableStateOf<SymbolState?>(null) }
    if (opened != null && opened != state) SideEffect { opened = null }
    val open = openApp(feed)
    // The app a long press opens: the shown player's, else the last followed, else the first switched on.
    val target = player ?: last?.takeIf { settings?.follows(it) == true } ?: firstOn?.packageName
    val onHold = {
        if (state == SymbolState.NONE_ENABLED || target == null) {
            opened = state
        } else {
            opened = null
            open(target, state == SymbolState.PLAYER)
        }
    }
    val flashing = flashesIcon(feed, player, playback.status == PlaybackStatus.PLAYING)
    Box {
        SymbolButton(
            state,
            icon = flashing,
            apps = apps,
            label = feed.label,
            onTap = { opened = if (opened == null) state else null },
            onHold = onHold,
        )
        DropdownMenu(expanded = opened != null, onDismissRequest = { opened = null }) {
            SymbolPopup(opened, feed, apps, close = { opened = null }, onOpenPlayers)
        }
    }
}

// What the symbol opened ([opened]): the player menu on the shown player, or a panel; [close] closes it.
@Composable
private fun SymbolPopup(
    opened: SymbolState?,
    feed: HeaderPlayerFeed,
    apps: PlayerApps,
    close: () -> Unit,
    onOpenPlayers: () -> Unit,
) {
    val playback by feed.playback.collectAsState()
    when (opened) {
        SymbolState.PLAYER ->
            playback.player?.let { PlayerMenu(feed, playback, remember(it) { feed.label(it) }, apps, close) }
        SymbolState.NOTHING_PLAYING -> SymbolPanel(close) { NothingPlayingText() }
        SymbolState.NONE_ENABLED ->
            SymbolPanel(close) {
                NoneEnabledText {
                    close()
                    onOpenPlayers()
                }
            }
        null -> {}
    }
}

// What the symbol stands for, with [player]'s session shown, or none, and the players' [settings].
private fun symbolState(
    player: String?,
    settings: Settings?,
): SymbolState =
    when {
        player != null -> SymbolState.PLAYER
        settings?.let { s -> s.followedPlayers.keys.none(s::follows) } == true -> SymbolState.NONE_ENABLED
        else -> SymbolState.NOTHING_PLAYING
    }

// Opens a player's app: its session's screen too [withScreen], else its launcher entry; a message
// when neither opens, as the menu's *Open <player>*.
@Composable
private fun openApp(feed: HeaderPlayerFeed): (String, Boolean) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    return { packageName, withScreen ->
        val activity = if (withScreen) feed.activity(packageName) else null
        if (!openPlayer(context, activity, feed.launch(packageName))) {
            val none = resources.getString(R.string.home_player_no_screen, feed.label(packageName))
            Toast.makeText(context, none, Toast.LENGTH_SHORT).show()
        }
    }
}

// The symbol itself for [state]: the note, dimmed but for [SymbolState.PLAYER], or [icon]'s own icon
// while it shows; [onTap] and [onHold] on a tap and a long press, each named for a screen reader.
@Suppress("LongParameterList") // The state, what it draws, and its two gestures.
@Composable
private fun SymbolButton(
    state: SymbolState,
    icon: String?,
    apps: PlayerApps,
    label: (String) -> String,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val name = stringResource(R.string.home_player_symbol)
    val dimmedState =
        when (state) {
            SymbolState.PLAYER -> null
            SymbolState.NOTHING_PLAYING -> stringResource(R.string.home_player_symbol_nothing_playing_state)
            SymbolState.NONE_ENABLED -> stringResource(R.string.home_player_symbol_none_enabled_state)
        }
    Box(
        Modifier
            .size(TARGET)
            .testTag(PLAYER_SYMBOL_TAG)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = TARGET / 2),
                role = Role.Button,
                onClickLabel = stringResource(R.string.home_player_symbol_tap),
                onLongClickLabel = stringResource(R.string.home_player_symbol_hold),
                onLongClick = onHold,
                onClick = onTap,
            ).semantics {
                contentDescription = name
                dimmedState?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            PlayerIcon(icon, label(icon), apps, ICON, Modifier.testTag(PLAYER_SYMBOL_ICON_TAG))
        } else {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                modifier = Modifier.alpha(if (dimmedState != null) DIMMED else 1f),
            )
        }
    }
}

/**
 * The player whose own icon the symbol shows now, or null for the note (#1440): [player], for
 * [FLASH_MS] once it starts playing ([playing]) or Home switches to it from another player, never on
 * the first look at Home, and only with motion: the phone's animations on and *Spin the logo* on.
 * Noticing the change and timing the second are apart, so a pause or another report within the
 * second does not cut it short; it ends early only when Home leaves, the player goes, or motion is
 * switched off, and switching motion back on starts none. No timer of its own besides.
 */
@Composable
private fun flashesIcon(
    feed: HeaderPlayerFeed,
    player: String?,
    playing: Boolean,
): String? {
    val settings by feed.settings.collectAsState()
    val motion = animatorDurationScale() > 0f && settings?.spinLogo == true
    val moving by rememberUpdatedState(motion)
    // The player whose icon shows, and each flash's own number, which its timer goes by.
    var flashing by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableIntStateOf(0) }
    // What the last look saw: the player and whether it played; none before the first.
    val seen = remember { arrayOfNulls<Pair<String?, Boolean>>(1) }
    LaunchedEffect(player, playing) {
        val before = seen[0]
        seen[0] = player to playing
        if (player != flashing) flashing = null
        if (moving && before != null && flashes(before, player, playing)) {
            flashing = player
            flash++
        }
    }
    LaunchedEffect(flash) {
        if (flashing == null) return@LaunchedEffect
        delay(FLASH_MS)
        flashing = null
    }
    if (!motion && flashing != null) SideEffect { flashing = null }
    return flashing.takeIf { motion && it == player }
}

// Whether a player [player], [playing] or not, after [before] (the player then, and whether it
// played), calls for its icon: another player shown in place of one, or a player that starts playing.
private fun flashes(
    before: Pair<String?, Boolean>,
    player: String?,
    playing: Boolean,
): Boolean {
    val (was, wasPlaying) = before
    if (player == null) return false
    val switched = was != null && player != was
    val started = playing && !(player == was && wasPlaying)
    return switched || started
}

// A panel the dimmed symbol opens (#1440): its [text], and under it, on the right, *Dismiss*, which
// only closes it ([close]).
@Composable
private fun SymbolPanel(
    close: () -> Unit,
    text: @Composable () -> Unit,
) {
    Column(
        Modifier.widthIn(max = PANEL_WIDTH).padding(horizontal = 16.dp, vertical = 8.dp).testTag(SYMBOL_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        text()
        PillButton(
            stringResource(R.string.home_player_symbol_dismiss),
            onClick = close,
            modifier = Modifier.align(Alignment.End).testTag(SYMBOL_PANEL_DISMISS_TAG),
        )
    }
}

@Composable
private fun NothingPlayingText() {
    Text(stringResource(R.string.home_player_symbol_nothing_playing), style = MaterialTheme.typography.bodyMedium)
}

// *No player enabled*, with *Settings → Players* a link there ([toPlayers]).
@Composable
private fun NoneEnabledText(toPlayers: () -> Unit) {
    val link = TextPart.Action(stringResource(R.string.home_players_none_link), SYMBOL_PLAYERS_LINK_TAG, toPlayers)
    LinkedText(
        linkedParts(stringResource(R.string.home_player_symbol_none_enabled), link),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The test tag of the player symbol in Home's header (#1440). */
const val PLAYER_SYMBOL_TAG: String = "home-player-symbol"

/** The test tag of the player's own icon while it shows in the symbol's place (#1440). */
const val PLAYER_SYMBOL_ICON_TAG: String = "home-player-symbol-icon"

/** The test tag of the panel the dimmed symbol opens (#1440). */
const val SYMBOL_PANEL_TAG: String = "home-player-symbol-panel"

/** The test tag of the panel's *Dismiss*. */
const val SYMBOL_PANEL_DISMISS_TAG: String = "home-player-symbol-dismiss"

/** The test tag of the panel's link to *Settings → Players*. */
const val SYMBOL_PLAYERS_LINK_TAG: String = "home-player-symbol-players"

/** The symbol's touch target: Material's least, as the header's other buttons. */
private val TARGET = 48.dp

/** The player's icon in the symbol's place for a moment: a little larger than the note. */
private val ICON = 26.dp

/** How much of the symbol shows while dimmed: Material's disabled content. */
private const val DIMMED = 0.38f

/** How long the player's icon shows in the symbol's place. */
private const val FLASH_MS = 1_000L

/** The panel's widest, so its text wraps as a note does. */
private val PANEL_WIDTH = 280.dp
