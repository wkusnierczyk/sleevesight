package app.sleevesight.app.ui.settings

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A player's icon under *Players*, as Android's own app lists draw it (#535). */
private val PLAYER_ICON = 40.dp

/** The players seen, where they are not known (tests, previews). */
private val NONE_SEEN: StateFlow<Set<String>> = MutableStateFlow(emptySet())

/**
 * The players' apps as the phone has them (#535): [icon] is a player's own icon, null for one the
 * phone does not show this app, asked off the main thread; [visible] says whether the phone shows
 * it (installed, and visible through the manifest's `<queries>`); [seen] holds the players that
 * reported music since the process started, which are on the phone whether or not it shows them.
 */
class InstalledPlayers(
    val icon: (String) -> Drawable?,
    val visible: (String) -> Boolean,
    val seen: StateFlow<Set<String>>,
)

/**
 * *Players* (#444): a switch per player that has reported music, with its app's icon at the row's
 * start (#535), by its name, and under the name *Open the app ↗*, which opens the player, for one
 * that has a launcher entry (visible through the manifest's `<queries>`, #435). The package name
 * shows under the name only in developer mode. The icon is decorative: the name beside it says
 * which app it is.
 *
 * Listed: each stored player the phone shows this app, or that reported music since the process
 * started, so a followed player that plays always has its switch. One that is neither, Spotify
 * (listed by default) on a phone without it included, is hidden, and its entry kept: not being
 * shown is no proof the app is gone (`PlayerRemovedReceiver` drops an uninstalled one). A row's
 * icon loads off the main thread, once per row while the section is drawn; the row shows without
 * it until then, and for good when the phone has none to give. With no [PlayerApps.installed]
 * (tests, previews), every stored player is listed, without an icon.
 */
@Composable
internal fun PlayersSection(
    settings: Settings,
    apps: PlayerApps,
    update: Update,
) {
    val installed = apps.installed
    val seen = (installed?.seen ?: NONE_SEEN).collectAsState().value
    val players = settings.followedPlayers.keys
    val visible = installed?.let { remember(players) { players.filterTo(mutableSetOf(), it.visible) } }
    val listed = settings.followedPlayers.toSortedMap().filterKeys { visible == null || it in visible || it in seen }
    if (listed.isEmpty()) Hint(R.string.settings_players_none)
    for ((player, followed) in listed) {
        key(player) { PlayerRow(player, followed, settings.developerMode, apps, update) }
    }
}

@Composable
private fun PlayerRow(
    player: String,
    followed: Boolean,
    developerMode: Boolean,
    apps: PlayerApps,
    update: Update,
) {
    val context = LocalContext.current
    val name = apps.label(player)
    val launch = remember(player) { apps.launch(player) }
    val size = with(LocalDensity.current) { PLAYER_ICON.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, player, size) {
        val load = apps.installed?.icon ?: return@produceState
        value = withContext(Dispatchers.IO) { load(player)?.bitmap(size) }
    }
    SwitchRow(
        title = name,
        summary = null,
        checked = followed,
        leading =
            icon?.let { loaded ->
                { Image(loaded, contentDescription = null, modifier = Modifier.size(PLAYER_ICON)) }
            },
        below = {
            // The package name, only in developer mode, and marked as such (#536).
            if (developerMode) {
                DeveloperMark { Text(player, style = MaterialTheme.typography.bodySmall) }
            }
            launch?.let { intent ->
                ExternalLink(
                    text = stringResource(R.string.settings_open_app),
                    description = stringResource(R.string.settings_open_app_of, name),
                    onClick = {
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // Uninstalled since the list was drawn: nothing to open.
                        }
                    },
                )
            }
        },
    ) { on ->
        update { it.copy(followedPlayers = it.followedPlayers + (player to on)) }
    }
}

/**
 * The icon drawn at [size] pixels a side, as the launcher draws it: an adaptive icon under the
 * phone's mask, which `AdaptiveIconDrawable` applies as it draws. Off the main thread: a drawable of
 * its own, drawn into a bitmap of its own.
 */
private fun Drawable.bitmap(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, size, size)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
