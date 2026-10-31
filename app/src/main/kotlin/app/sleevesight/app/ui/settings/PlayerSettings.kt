package app.sleevesight.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.sleevesight.app.R
import app.sleevesight.app.settings.Dismissals
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.PLAYERS_FAQ_URL
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.app.ui.theme.ActionButtonGroup
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.app.ui.theme.GroupAction
import app.sleevesight.app.ui.theme.LocalMessages
import app.sleevesight.core.settings.Dismissable
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

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
 * Listed as [listed], which the screen asks [listedPlayers] for once, for the search too (#649;
 * #627: A to Z by name, as Home lists them): each stored player the phone shows this app, or that
 * reported music since the process started, so a followed player that plays always has its switch.
 * One that is neither, Spotify (listed by default) on a phone without it included, is hidden, and
 * its entry kept: not being shown is no proof the app is gone (`PlayerRemovedReceiver` drops an
 * uninstalled one). The rows show once the list's names and
 * links have loaded, off the main thread; each row's icon loads after, off the main thread too,
 * once per row while the section is drawn (#579): the row shows without it until it has, and for
 * good when the phone has none to give. With no [PlayerApps.installed] (tests, previews), every
 * stored player is listed, without an icon. Under the players, *Can’t see your player?*
 * ([MissingPlayerNote], #758), until its *Hide once* puts it away until the section is opened
 * again, or its *Dismiss* for good ([dismissals], #759), then *Only enabled players on Home* (#674).
 */
@Composable
internal fun PlayersSection(
    settings: Settings,
    listed: List<ListedPlayer>?,
    apps: PlayerApps,
    update: Update,
    dismissals: Dismissals,
) {
    // Asked once, by the screen, for the search too (#649); null until loaded.
    if (listed == null) return
    if (listed.isEmpty()) Hint(R.string.settings_players_none)
    for (player in listed) {
        val followed = settings.followedPlayers[player.packageName] ?: false
        key(player.packageName) { PlayerRow(player, followed, settings.developerMode, apps, update) }
    }
    // After the players, the section's own content, so it stays put as players are added (#674).
    // Hidden once (#759): kept through a rotation, and forgotten with the section's panel, so the
    // note is back once *Players* is closed, or the settings left, and opened again.
    var hiddenOnce by rememberSaveable { mutableStateOf(false) }
    if (Dismissable.PLAYERS_NOTE !in settings.dismissed && !hiddenOnce) {
        MissingPlayerNote(dismissals) { hiddenOnce = true }
    }
    SwitchRow(
        title = stringResource(R.string.settings_players_on_home),
        summary = stringResource(R.string.settings_players_on_home_summary),
        checked = settings.onlyEnabledPlayersOnHome,
    ) { on -> update { it.copy(onlyEnabledPlayersOnHome = on) } }
}

/**
 * *Can’t see your player?* (#758): what a player needs to be listed, in a panel of the theme's
 * container colour, as the first-run screen's access panel is, so it stands apart from the rows; its
 * heading, the note's paragraphs, and *Which players work ↗*, which opens the FAQ's answer in the
 * browser; at its bottom right, *Hide once* and *Dismiss* (#759), one connected group, as the
 * home guide's: *Hide once* ([onHideOnce]) puts it away for this visit to the section, *Dismiss*
 * for good ([Dismissals.dismiss]), then a message says where to bring it back, with *Undo*
 * ([Dismissals.undo]). A search shows it while it matches its heading or its text.
 */
@Composable
private fun MissingPlayerNote(
    dismissals: Dismissals,
    onHideOnce: () -> Unit,
) {
    val title = stringResource(R.string.settings_players_missing_title)
    if (!shownInSearch(title)) return
    val open = rememberOpenUrl(PLAYERS_FAQ_URL)
    val messages = LocalMessages.current
    val dismissed = stringResource(R.string.settings_players_missing_dismissed)
    val undo = stringResource(R.string.undo)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(MISSING_PLAYER_NOTE_TAG),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                searchMarked(title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Paragraphs(
                stringResource(R.string.settings_players_missing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExternalLink(
                text = stringResource(R.string.settings_players_missing_link),
                description = stringResource(R.string.settings_players_missing_link_description),
                onClick = open,
            )
            ActionButtonGroup(
                listOf(
                    GroupAction(
                        stringResource(R.string.settings_players_missing_hide_once),
                        modifier = Modifier.testTag(MISSING_PLAYER_NOTE_HIDE_ONCE_TAG),
                        onClick = onHideOnce,
                    ),
                    GroupAction(
                        stringResource(R.string.settings_players_missing_dismiss),
                        modifier = Modifier.testTag(MISSING_PLAYER_NOTE_DISMISS_TAG),
                    ) {
                        val saved = dismissals.dismiss(Dismissable.PLAYERS_NOTE)
                        messages?.showOnceSaved(saved, dismissed, undo) { dismissals.undo(Dismissable.PLAYERS_NOTE) }
                    },
                ),
                compact = Alignment.End,
            )
        }
    }
}

/** The test tag of *Can’t see your player?* ([MissingPlayerNote]). */
internal const val MISSING_PLAYER_NOTE_TAG = "settings:missingPlayerNote"

/** The test tag of the note's *Hide once* (#759). */
internal const val MISSING_PLAYER_NOTE_HIDE_ONCE_TAG = "settings:missingPlayerNoteHideOnce"

/** The test tag of the note's *Dismiss* (#759). */
internal const val MISSING_PLAYER_NOTE_DISMISS_TAG = "settings:missingPlayerNoteDismiss"

/**
 * A player as *Players* and Home list it (#627): its [packageName], its [name] as the phone shows
 * it, and the [launch] intent that opens it from its launcher entry, or null for one with none.
 */
internal data class ListedPlayer(
    val packageName: String,
    val name: String,
    val launch: Intent?,
)

/**
 * The stored [players] that *Players* and Home list, A to Z by name ([byName]), or null until they
 * have loaded: each the phone shows this app ([InstalledPlayers.visible]), or that reported music
 * since the process started ([InstalledPlayers.seen]); all of them with no [PlayerApps.installed].
 * Their names, links and whether the phone shows them are asked off the main thread, once per change
 * to the players stored or seen, and again each time the screen resumes, so a player installed
 * meanwhile shows on return (#579). The list stays as it was while it is asked again.
 */
@Composable
internal fun listedPlayers(
    players: Set<String>,
    apps: PlayerApps,
): List<ListedPlayer>? {
    val installed = apps.installed
    val seen = (installed?.seen ?: NONE_SEEN).collectAsState().value
    // The effect also runs on the first resume after the first draw, which needs no second ask: -1 to 0.
    var resumes by remember { mutableIntStateOf(-1) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val asked = maxOf(resumes, 0)
    val listed by produceState<List<ListedPlayer>?>(null, players, seen, asked) {
        value =
            withContext(Dispatchers.IO) {
                players
                    .filter { installed == null || it in seen || installed.visible(it) }
                    .map { ListedPlayer(it, apps.label(it), apps.launch(it)) }
                    .sortedWith(byName())
            }
    }
    return listed
}

/**
 * The order *Players* and Home list players in (#627): by name, as the phone's language sorts it,
 * whatever the case; players of the same name by package name, so the order stays put.
 */
internal fun byName(locale: Locale = Locale.getDefault()): Comparator<ListedPlayer> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return compareBy<ListedPlayer, String>(collator) { it.name }.thenBy { it.packageName }
}

@Composable
private fun PlayerRow(
    player: ListedPlayer,
    followed: Boolean,
    developerMode: Boolean,
    apps: PlayerApps,
    update: Update,
) {
    val context = LocalContext.current
    val icon = playerIcon(player.packageName, apps)
    val (packageName, name, launch) = player
    SwitchRow(
        title = name,
        summary = null,
        checked = followed,
        leading =
            icon?.let { bitmap ->
                { Image(bitmap, contentDescription = null, modifier = Modifier.size(ROW_MARK)) }
            },
        below = {
            // The package name, only in developer mode, and marked as such (#536).
            if (developerMode) {
                DeveloperMark { Text(packageName, style = MaterialTheme.typography.bodySmall) }
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
        update { it.copy(followedPlayers = it.followedPlayers + (packageName to on)) }
    }
}

/**
 * [packageName]'s own icon, [ROW_MARK] a side, as the phone has it, asked off the main thread once
 * per row; null until it has loaded, and for good when the phone has none to give or there is no
 * [PlayerApps.installed].
 */
@Composable
internal fun playerIcon(
    packageName: String,
    apps: PlayerApps,
): ImageBitmap? {
    val size = with(LocalDensity.current) { ROW_MARK.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName, size) {
        val load = apps.installed?.icon ?: return@produceState
        value = withContext(Dispatchers.IO) { load(packageName)?.bitmap(size) }
    }
    return icon
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
