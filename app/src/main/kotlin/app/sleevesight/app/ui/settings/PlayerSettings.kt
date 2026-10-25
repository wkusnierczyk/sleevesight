package app.sleevesight.app.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.core.settings.Settings

/**
 * *Players* (#444): a switch per player that has reported music, by its name, and under the name
 * *Open the app ↗*, which opens the player, for one that has a launcher entry (visible through the
 * manifest's `<queries>`, #435). The package name shows under the name only in developer mode.
 */
@Composable
internal fun PlayersSection(
    settings: Settings,
    apps: PlayerApps,
    update: Update,
) {
    val context = LocalContext.current
    for ((player, followed) in settings.followedPlayers.toSortedMap()) {
        val name = apps.label(player)
        val launch = remember(player) { apps.launch(player) }
        SwitchRow(
            title = name,
            summary = player.takeIf { settings.developerMode },
            checked = followed,
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
}
