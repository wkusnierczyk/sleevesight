package app.sleevesight.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.settings.ListedPlayer
import app.sleevesight.app.ui.settings.PlayerApps
import app.sleevesight.app.ui.settings.ROW_MARK
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.settings.listedPlayers
import app.sleevesight.app.ui.settings.playerIcon
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.settings.Settings

/**
 * Home with nothing playing in a followed player (#627), for previews and the navigation's default:
 * the stored players of [Settings]' defaults, named by their packages, and the guide.
 */
@Composable
fun NothingPlaying() {
    IdleHome(Settings(), guideDismissed = false, NowPlayingActions(normalize = { error("Nothing plays") }))
}

/**
 * Home with nothing playing in a followed player (#627): a row per player *Settings → Players*
 * lists, in its order, A to Z by name ([listedPlayers]); a followed one's row opens the player from
 * its launcher entry, as *Players*' *Open the app* does, and a switched-off one's is dimmed, cannot be
 * tapped, and links to *Players* (*Enable in Settings*). With no player listed, a line says so, and
 * links to *Players*. With *Only enabled players on Home* on (#674), only the followed players are
 * listed; with players listed but none of them followed, a line says none is switched on, and links
 * to *Players*. Under the players, the guide to Home ([HomeGuide]) while *Home guide* is on and
 * it is not [guideDismissed]. Nothing before the settings' first read ([settings] null), nor before
 * the players have loaded.
 */
@Composable
internal fun IdleHome(
    settings: Settings?,
    guideDismissed: Boolean,
    actions: NowPlayingActions,
) {
    if (settings == null) return
    val apps = remember(actions) { PlayerApps(actions.playerLabel, actions.playerLaunch, actions.installedPlayers) }
    val listed = listedPlayers(settings.followedPlayers.keys, apps) ?: return
    val shown = if (settings.onlyEnabledPlayersOnHome) listed.filter { settings.follows(it.packageName) } else listed
    val toPlayers = { actions.openSettingsAt(SettingsTarget.players) }
    Column(Modifier.fillMaxWidth().testTag(NOTHING_PLAYING_TAG)) {
        if (shown.isEmpty()) {
            val line = if (listed.isEmpty()) R.string.home_players_none else R.string.home_players_none_enabled
            val link = TextPart.Action(stringResource(R.string.home_players_none_link), PLAYERS_LINK_TAG, toPlayers)
            LinkedText(
                linkedParts(stringResource(line), link),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        for (player in shown) {
            key(player.packageName) {
                IdlePlayerRow(player, settings.follows(player.packageName), apps, toPlayers)
            }
        }
        if (settings.homeGuide && !guideDismissed) {
            Spacer(Modifier.height(GUIDE_SPACE))
            HomeGuide(
                onDismissOnce = actions.dismissGuide,
                onNever = actions.hideGuide,
                onReenable = { actions.openSettingsAt(SettingsTarget.homeGuide) },
            )
        }
    }
}

/**
 * A player's row on the idle Home (#627): its icon, *Play on <player>* and the open-in-new icon,
 * a button that opens the player; [followed] off, dimmed, not a button, without the open-in-new
 * icon, and under its name *Enable in Settings*, a link to *Players* ([toPlayers]). A screen reader
 * reads a switched-off one as *Play on <player>, not followed*, then the link.
 */
@Composable
private fun IdlePlayerRow(
    player: ListedPlayer,
    followed: Boolean,
    apps: PlayerApps,
    toPlayers: () -> Unit,
) {
    val context = LocalContext.current
    val icon = playerIcon(player.packageName, apps)
    val name = stringResource(R.string.home_play_on, player.name)
    val open = stringResource(R.string.home_open_player, player.name)
    val none = stringResource(R.string.home_player_no_screen, player.name)
    val notFollowed = stringResource(R.string.home_play_on_not_followed, player.name)
    val shape = RoundedCornerShape(10.dp)
    var row =
        Modifier
            .fillMaxWidth()
            .testTag(idlePlayerTag(player.packageName))
            .heightIn(min = ROW_HEIGHT)
            .clip(shape)
    if (followed) {
        row =
            row.clickable(onClickLabel = open, role = Role.Button) {
                if (!openPlayer(context, null, player.launch)) Toast.makeText(context, none, Toast.LENGTH_SHORT).show()
            }
    }
    Row(
        row.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val dim = Modifier.alpha(if (followed) 1f else DIMMED)
        Box(dim.size(ROW_MARK)) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(ROW_MARK)) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                modifier = if (followed) dim else dim.clearAndSetSemantics { contentDescription = notFollowed },
            )
            if (!followed) {
                LinkLabel(
                    TextPart.Action(
                        stringResource(R.string.home_player_enable),
                        enableTag(player.packageName),
                        toPlayers,
                    ),
                    onClickLabel = stringResource(R.string.home_players_open_settings),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (followed) {
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The test tag of the idle Home: the players and the guide. */
const val NOTHING_PLAYING_TAG: String = "nothing-playing"

/** The test tag of the link to *Players* in the line shown when no player is listed. */
const val PLAYERS_LINK_TAG: String = "home-players-link"

/** The test tag of the idle Home's row for [packageName]. */
fun idlePlayerTag(packageName: String): String = "home-play-on-$packageName"

/** The test tag of the *Enable in Settings* link of [packageName]'s row. */
fun enableTag(packageName: String): String = "home-enable-$packageName"

/** A row's least height: its icon, and room around it. */
private val ROW_HEIGHT = 56.dp

/** The space above the guide: a little more than between the rows. */
private val GUIDE_SPACE = 16.dp

/** How much of a switched-off player's icon and name shows. */
private const val DIMMED = 0.55f
