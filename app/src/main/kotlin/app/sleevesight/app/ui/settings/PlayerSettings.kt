package app.sleevesight.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.sleevesight.app.R
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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
 * Listed: each stored player the phone shows this app (asked as the section is drawn, and again
 * each time the screen resumes), or that reported music since the process started, so a followed
 * player that plays always has its switch. One that is neither, Spotify (listed by default) on a
 * phone without it included, is hidden, and its entry kept: not being shown is no proof the app is
 * gone (`PlayerRemovedReceiver` drops an uninstalled one). A row's name, link and icon load off the
 * main thread, once per row while the section is drawn (#579): the row shows once its name and link
 * have loaded, without the icon until it has, and for good when the phone has none to give. With no
 * [PlayerApps.installed] (tests, previews), every stored player is listed, without an icon.
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
    // Asked again on each resume, so a player installed while *Players* is open shows on return (#579).
    // The effect also runs on the first resume after the first draw, which needs no second ask: -1 to 0.
    var resumes by remember { mutableIntStateOf(-1) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val asked = maxOf(resumes, 0)
    val visible = installed?.let { remember(players, asked) { players.filterTo(mutableSetOf(), it.visible) } }
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
    val size = with(LocalDensity.current) { ROW_MARK.roundToPx() }
    val loaded by produceState<PlayerApp?>(null, player, size) {
        val app = withContext(Dispatchers.IO) { PlayerApp(apps.label(player), apps.launch(player)) }
        value = app
        val load = apps.installed?.icon ?: return@produceState
        value = app.copy(icon = withContext(Dispatchers.IO) { load(player)?.bitmap(size) })
    }
    // The row shows once its name is known, so a package name never stands in for it.
    val (name, launch, icon) = loaded ?: return
    SwitchRow(
        title = name,
        summary = player.takeIf { developerMode },
        checked = followed,
        leading =
            icon?.let { bitmap ->
                { Image(bitmap, contentDescription = null, modifier = Modifier.size(ROW_MARK)) }
            },
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
 * What the phone says of a player's app, for its row: its [name], the [launch] intent that opens it,
 * and its [icon], once loaded. Package-manager calls, all made off the main thread.
 */
private data class PlayerApp(
    val name: String,
    val launch: Intent?,
    val icon: ImageBitmap? = null,
)

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
