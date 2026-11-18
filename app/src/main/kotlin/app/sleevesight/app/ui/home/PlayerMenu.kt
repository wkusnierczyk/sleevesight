package app.sleevesight.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.session.Transport
import app.sleevesight.app.ui.settings.ListedPlayer
import app.sleevesight.app.ui.settings.PlayerApps
import app.sleevesight.app.ui.settings.listedPlayers
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.core.settings.Settings

// The player menu (#1209), which the followed player's icon in Home's header opens ([PlayerMenuButton]).

/**
 * The player menu (#1209), for the shown player ([playback], named [name]): its name and state;
 * Previous, Play or Pause, and Next, pills, each dimmed and not a button unless the player advertises
 * it, and under them, when it advertises none, that it takes no controls from other apps; *Open
 * <player>*, which opens the player's own screen, else its launcher entry, as the player row above
 * the cover did (#596), and closes the menu; then, with more than one, the players *Settings →
 * Players* lists, as Home lists them while nothing plays (*Only enabled players on Home*, #674),
 * the shown one selected, each with its state, or *Open the app ↗* with no session. Choosing one with
 * a session makes Home show it ([HeaderPlayerFeed.follow]), and the menu's state and controls go
 * with it; choosing one with none opens its app. The menu stays open through the controls and a
 * choice of player; it closes on *Open <player>*, a tap outside, or Back ([close]).
 */
@Composable
internal fun PlayerMenu(
    feed: HeaderPlayerFeed,
    playback: PlaybackInfo,
    name: String,
    apps: PlayerApps,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val player = checkNotNull(playback.player)
    val state = stateText(playback.status)
    val none = stringResource(R.string.home_player_no_screen, name)
    Column(Modifier.widthIn(min = MENU_WIDTH).testTag(PLAYER_MENU_OPEN_TAG)) {
        Text(
            if (state == null) name else stringResource(R.string.home_player_state, name, state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        MenuControls(playback, name, feed.send)
        MenuRow(
            onClick = {
                close()
                if (!openPlayer(context, feed.activity(), feed.launch(player))) {
                    Toast.makeText(context, none, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.padding(top = 8.dp).testTag(PLAYER_MENU_OPEN_PLAYER_TAG),
        ) {
            Text(stringResource(R.string.home_open_player, name), modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
        }
        PlayersList(feed, player, apps)
    }
}

// Previous, Play or Pause, and Next for [playback]'s player, named [name], each dimmed unless it
// advertises it; under them, when it advertises none, that it takes no controls from other apps.
@Composable
private fun MenuControls(
    playback: PlaybackInfo,
    name: String,
    send: (Transport) -> Unit,
) {
    val controls = listOf(Transport.PREVIOUS, playback.toggle, Transport.NEXT)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        for (control in controls) TransportPill(control, playback.allows(control)) { send(control) }
    }
    if (controls.none(playback::allows)) {
        Text(
            stringResource(R.string.home_player_no_controls, name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
        )
    }
}

// The menu's *Players*, with more than one listed: each with its state, [shown] selected; one with
// a session followed when chosen, one with none opened from its launcher entry.
@Composable
private fun PlayersList(
    feed: HeaderPlayerFeed,
    shown: String,
    apps: PlayerApps,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val followed by feed.followed.collectAsState()
    val settings by feed.settings.collectAsState()
    val listed = settings?.let { menuPlayers(it, apps) }.orEmpty()
    if (listed.size <= 1) return
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(
        stringResource(R.string.home_players),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
    )
    Column(Modifier.selectableGroup()) {
        for (other in listed) {
            PlayerChoice(other, followed[other.packageName], other.packageName == shown, apps) {
                if (other.packageName in followed) {
                    feed.follow(other.packageName)
                } else if (!openPlayer(context, null, other.launch)) {
                    val none = resources.getString(R.string.home_player_no_screen, other.name)
                    Toast.makeText(context, none, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** The players the menu lists: as Home lists them while nothing plays ([IdleHome]), or null until loaded. */
@Composable
private fun menuPlayers(
    settings: Settings,
    apps: PlayerApps,
): List<ListedPlayer>? {
    val listed = listedPlayers(settings.followedPlayers.keys, apps) ?: return null
    return if (settings.onlyEnabledPlayersOnHome) listed.filter { settings.follows(it.packageName) } else listed
}

// One of the menu's controls: a pill with [control]'s symbol, read by its name; dimmed and not a
// button unless [allowed]. A tap leaves the menu open.
@Composable
private fun TransportPill(
    control: Transport,
    allowed: Boolean,
    onClick: () -> Unit,
) {
    val (icon, label) =
        when (control) {
            Transport.PREVIOUS -> R.drawable.ic_skip_previous to R.string.home_previous
            Transport.PLAY -> R.drawable.ic_play_arrow to R.string.home_record_play
            Transport.PAUSE -> R.drawable.ic_pause to R.string.home_record_pause
            Transport.NEXT -> R.drawable.ic_skip_next to R.string.home_next
        }
    PillButton(
        onClick = onClick.takeIf { allowed },
        named = stringResource(label),
        modifier = Modifier.testTag(transportTag(control)),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

// A row of the menu, 48 dp at least, [onClick] on a tap.
@Composable
private fun MenuRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// A player in the menu: its icon, its name, its state ([status], null with no session: *Open the app
// ↗*) and a radio, [selected] for the one shown; read as "<player>, <state>", one choice of several.
@Composable
private fun PlayerChoice(
    player: ListedPlayer,
    status: PlaybackStatus?,
    selected: Boolean,
    apps: PlayerApps,
    onClick: () -> Unit,
) {
    val state = status?.let { stateText(it) }
    val detail = if (status == null) stringResource(R.string.home_player_open_app) else state
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(menuPlayerTag(player.packageName))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(player.name, detail).joinToString(", ")
                this.selected = selected
                role = Role.RadioButton
                onClick {
                    onClick()
                    true
                }
            }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayerIcon(player.packageName, player.name, apps, CHOICE_ICON)
        // The state under the name, so a long name or a large font keeps its words whole.
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(player.name)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RadioButton(
            selected = selected,
            onClick = null,
            colors =
                RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.onSurface,
                    unselectedColor = MaterialTheme.colorScheme.onSurface,
                ),
        )
    }
}

/** The test tag of the open player menu's content. */
const val PLAYER_MENU_OPEN_TAG: String = "home-player-menu-open"

/** The test tag of the player menu's *Open <player>*. */
const val PLAYER_MENU_OPEN_PLAYER_TAG: String = "home-player-menu-open-player"

/** The test tag of the player menu's pill for [control]. */
fun transportTag(control: Transport): String = "home-transport-${control.name}"

/** The test tag of the player menu's row for [packageName]. */
fun menuPlayerTag(packageName: String): String = "home-menu-player-$packageName"

/** A player's icon in the menu's list. */
private val CHOICE_ICON = 24.dp

/** The menu's least width, so the controls and the players' states fit on a row. */
private val MENU_WIDTH = 240.dp
