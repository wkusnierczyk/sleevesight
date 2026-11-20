package app.sleevesight.app.ui.home

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.logo.animatorDurationScale
import app.sleevesight.app.ui.settings.ListedPlayer
import app.sleevesight.app.ui.settings.PlayerApps
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.settings.SymbolOpens

// The player symbol in Home's header (#1440): a neutral music note, in place of the followed
// player's own icon (#1209), which looked foreign among the app's own marks.

/** What the header's player symbol stands for (#1440). */
private enum class SymbolState {
    /** A followed player has a session: the symbol opens the player menu. */
    PLAYER,

    /** Players are switched on in *Settings → Players*, but none has a session: dimmed. */
    NOTHING_PLAYING,

    /** No player *Settings → Players* lists is switched on, once they have loaded: dimmed. */
    NONE_ENABLED,
}

/**
 * The player symbol in Home's header (#1440): the music note alone, 48 dp, in the header buttons'
 * colour, shown always. One gesture opens the menu, the other the player: a tap the one *What the
 * symbol opens* names ([SymbolOpens], #1445; the menu by default), a long press the other. With a
 * followed player's session ([SymbolState.PLAYER]), the menu is the player menu under it
 * ([PlayerMenu]), which the same gesture closes, and the player is opened as the menu's *Open
 * <player>* opens it. With players switched on but none playing, it is dimmed: the menu's gesture
 * opens a panel that says nothing is playing, and the player's opens the last player followed
 * ([HeaderPlayerFeed.lastPlayer]), else the first one switched on. With no player switched on, it
 * is dimmed too, and either gesture opens a panel that says so and links to *Settings → Players*
 * ([onOpenPlayers]). A panel's *Dismiss*, a tap outside or Back closes it; the next tap opens it again.
 * While none plays, it takes no gesture until the players have loaded, which decide what one opens
 * (#1469).
 *
 * For [FLASH_MS], when a followed player starts playing, Home switches to another player, or the
 * track changes, the symbol shows that player's own icon ([PlayerIcon]) in place of the note
 * ([flashesIcon], #1445), faded in and out, or swapped at once with the phone's animations off; never
 * with *Show the current player's logo* off.
 *
 * A screen reader reads it as *Player menu*, or *Player* while a tap opens the player, a button, with
 * what a tap and a long press do, and, dimmed, *No player enabled* or *Nothing playing*; the flash is
 * not read.
 */
@Composable
internal fun PlayerSymbol(
    feed: HeaderPlayerFeed,
    onOpenPlayers: () -> Unit,
) {
    val playback by feed.playback.collectAsState()
    val settings by feed.settings.collectAsState()
    val nowPlaying by feed.nowPlaying.collectAsState()
    val player = playback.player
    val opens = settings?.symbolOpens ?: SymbolOpens.PLAYER_MENU
    val apps = remember(feed) { PlayerApps(feed.label, feed.launch, feed.installed) }
    // The players the menu lists; none switched on is said only once they have loaded.
    val listed = settings?.let { menuPlayers(it, apps) }
    val state = symbolState(player, listed)
    // The player last followed, kept while Home is away too, for a long press while none plays.
    val last by feed.lastPlayer.collectAsState()
    val firstOn = listed?.firstOrNull()
    // What is open: the menu, or a panel, as the state was at the tap; none when the state moves on.
    var opened by rememberSaveable { mutableStateOf<SymbolState?>(null) }
    if (opened != null && opened != state) SideEffect { opened = null }
    val open = openApp(feed)
    // The app the player's gesture opens: the shown player's, else the last followed, else the first switched on.
    val target = player ?: last?.takeIf { settings?.follows(it) == true } ?: firstOn?.packageName
    val toMenu = { opened = if (opened == null) state else null }
    val toPlayer = {
        if (state == SymbolState.NONE_ENABLED || target == null) {
            opened = state
        } else {
            opened = null
            open(target, state == SymbolState.PLAYER)
        }
    }
    val flashing = flashesIcon(feed, player, playing(playback), trackOf(nowPlaying))
    // What the popup last showed, kept while it closes, so its content goes with it.
    var shown by remember { mutableStateOf<SymbolState?>(null) }
    opened?.let { if (it != shown) SideEffect { shown = it } }
    Box {
        SymbolButton(
            state,
            opens,
            icon = flashing,
            apps = apps,
            label = feed.label,
            toMenu = toMenu,
            toPlayer = toPlayer,
            // While none plays, the players decide what a gesture opens: none before they have loaded (#1469).
            enabled = player != null || listed != null,
        )
        DropdownMenu(expanded = opened != null, onDismissRequest = { opened = null }) {
            SymbolPopup(opened ?: shown, feed, apps, close = { opened = null }, onOpenPlayers)
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
        SymbolState.NOTHING_PLAYING ->
            SymbolPanel(close) {
                Text(
                    stringResource(R.string.home_player_symbol_nothing_playing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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

// What the symbol stands for, with [player]'s session shown, or none, and the players the menu lists
// ([listed], null until loaded): none switched on there as *Settings → Players* shows them.
private fun symbolState(
    player: String?,
    listed: List<ListedPlayer>?,
): SymbolState =
    when {
        player != null -> SymbolState.PLAYER
        listed?.isEmpty() == true -> SymbolState.NONE_ENABLED
        else -> SymbolState.NOTHING_PLAYING
    }

// Whether [playback]'s player plays ([PlaybackInfo.plays]); null in a passing state (buffering, a
// skip, connecting), which keeps what was before, so a skip or a stall is no start.
private fun playing(playback: PlaybackInfo): Boolean? =
    when (playback.status) {
        PlaybackStatus.OTHER, PlaybackStatus.BUFFERING -> null
        else -> playback.plays
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
// while it shows, faded in and out, or swapped at once with the phone's animations off (#1445);
// [toMenu] on a tap and [toPlayer] on a long press, or the other way round as [opens] says, each
// named for a screen reader, and the symbol named for what a tap opens; neither unless [enabled].
@Suppress("LongParameterList") // The state, the choice, what it draws, and its two gestures.
@Composable
private fun SymbolButton(
    state: SymbolState,
    opens: SymbolOpens,
    icon: String?,
    apps: PlayerApps,
    label: (String) -> String,
    toMenu: () -> Unit,
    toPlayer: () -> Unit,
    enabled: Boolean,
) {
    val menuFirst = opens == SymbolOpens.PLAYER_MENU
    val name = stringResource(if (menuFirst) R.string.home_player_symbol else R.string.home_player_symbol_player)
    val menuLabel = stringResource(R.string.home_player_symbol_open_menu)
    val playerLabel = stringResource(R.string.home_player_symbol_open_player)
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
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (menuFirst) menuLabel else playerLabel,
                onLongClickLabel = if (menuFirst) playerLabel else menuLabel,
                onLongClick = if (menuFirst) toPlayer else toMenu,
                onClick = if (menuFirst) toMenu else toPlayer,
            ).semantics {
                contentDescription = name
                dimmedState?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        val content: @Composable (String?) -> Unit = { shown ->
            if (shown != null) {
                PlayerIcon(shown, label(shown), apps, ICON, Modifier.testTag(PLAYER_SYMBOL_ICON_TAG))
            } else {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    modifier = Modifier.testTag(PLAYER_SYMBOL_NOTE_TAG).alpha(if (dimmedState != null) DIMMED else 1f),
                )
            }
        }
        // With the phone's animations off, the icon still shows, swapped in and out at once (#1445).
        if (animatorDurationScale() > 0f) {
            Crossfade(icon, animationSpec = tween(FADE_MS), label = "player symbol") { content(it) }
        } else {
            content(icon)
        }
    }
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

/** The test tag of the symbol's note, while it shows (#1445). */
const val PLAYER_SYMBOL_NOTE_TAG: String = "home-player-symbol-note"

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

/** How long the note and the player's icon take to fade into each other, with the phone's animations on. */
internal const val FADE_MS = 220

/** The panel's widest, so its text wraps as a note does. */
private val PANEL_WIDTH = 280.dp
