package app.sleevesight.app.ui.settings

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import app.sleevesight.core.settings.Settings

/** A player's icon under *Players*, as Android's own app lists draw it (#535). */
private val PLAYER_ICON = 40.dp

/**
 * The players' apps as the phone has them (#535): [icon] is a player's own icon, null for a
 * player not installed (or not visible to this app), which *Players* does not list; and
 * [forgetUninstalled] drops such players from the stored list, run as *Players* is drawn.
 */
class InstalledPlayers(
    val icon: (String) -> Drawable?,
    val forgetUninstalled: suspend () -> Unit,
)

/**
 * *Players* (#444): a switch per player that has reported music and is installed, with its app's
 * icon at the row's start (#535), by its name, and under the name *Open the app ↗*, which opens
 * the player, for one that has a launcher entry (visible through the manifest's `<queries>`, #435).
 * The package name shows under the name only in developer mode. The icon is decorative: the name
 * beside it says which app it is. A player not installed is not listed, Spotify (listed by default)
 * included, and drawing the section drops the others' entries. With no [PlayerApps.installed]
 * (tests, previews), every stored player is listed, without an icon.
 */
@Composable
internal fun PlayersSection(
    settings: Settings,
    apps: PlayerApps,
    update: Update,
) {
    val installed = apps.installed
    installed?.let { LaunchedEffect(it) { it.forgetUninstalled() } }
    val size = with(LocalDensity.current) { PLAYER_ICON.roundToPx() }
    val players = settings.followedPlayers.keys
    val icons = installed?.let { remember(players, size) { players.associateWith { p -> it.icon(p)?.bitmap(size) } } }
    val listed = settings.followedPlayers.toSortedMap().filterKeys { icons == null || icons[it] != null }
    if (listed.isEmpty()) Hint(R.string.settings_players_none)
    for ((player, followed) in listed) {
        key(player) { PlayerRow(player, followed, icons?.get(player), settings.developerMode, apps, update) }
    }
}

@Suppress("LongParameterList") // The row's player, its switch and icon, and what names and opens it.
@Composable
private fun PlayerRow(
    player: String,
    followed: Boolean,
    icon: ImageBitmap?,
    developerMode: Boolean,
    apps: PlayerApps,
    update: Update,
) {
    val context = LocalContext.current
    val name = apps.label(player)
    val launch = remember(player) { apps.launch(player) }
    SwitchRow(
        title = name,
        summary = player.takeIf { developerMode },
        checked = followed,
        leading = icon?.let { { Image(it, contentDescription = null, modifier = Modifier.size(PLAYER_ICON)) } },
        below =
            launch?.let { intent ->
                {
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
 * phone's mask, which `AdaptiveIconDrawable` applies as it draws.
 */
private fun Drawable.bitmap(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, size, size)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
