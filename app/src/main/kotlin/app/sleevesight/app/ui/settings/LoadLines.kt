package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile

// The lines of *Load settings?* (#815): each setting a load changes, as a reset's lines are
// ([RESET_LINES]), and what of the file is not loaded, and why.

/**
 * The lines of a load beyond those of a reset ([RESET_LINES]): which AI service is switched on, and
 * each player's switch, the two settings a reset keeps but a file carries.
 */
internal val LOAD_ONLY_LINES: List<ResetLine> =
    listOf(
        ResetLine(listOf("aiProvider"), R.string.settings_section_ai) { now, loaded, _ ->
            OFFERED_PROVIDERS.flatMap { provider -> providerLine(provider, now, loaded) }
        },
    )

@Composable
private fun providerLine(
    provider: ProviderId,
    now: Settings,
    loaded: Settings,
): List<String> {
    val from = now.aiProvider == provider
    val to = loaded.aiProvider == provider
    if (from == to) return emptyList()
    return listOf(
        stringResource(R.string.settings_reset_line, stringResource(providerName(provider)), onOff(from), onOff(to)),
    )
}

/** The settings a file holds whose line is not one of [RESET_LINES] or [LOAD_ONLY_LINES]: *Players*' switches. */
internal const val PLAYERS_FIELD = "followedPlayers"

/** The title of the settings named [field], for a line under *Not loaded:*. */
internal fun fileTitle(field: String): Int? =
    if (field == PLAYERS_FIELD) {
        R.string.settings_section_players
    } else {
        (RESET_LINES + LOAD_ONLY_LINES).firstOrNull { field in it.fields }?.title
    }

/**
 * Each setting [now] has off [loaded], as *Title: value now → value loaded*, in the order the
 * settings show them, each player named by [playerLabel].
 */
@Composable
internal fun loadChanges(
    now: Settings,
    loaded: Settings,
    modelName: (String) -> String,
    playerLabel: (String) -> String,
): List<String> {
    val players =
        (now.followedPlayers.keys + loaded.followedPlayers.keys)
            .filter { now.follows(it) != loaded.follows(it) }
            .map { player ->
                stringResource(
                    R.string.settings_reset_line,
                    playerLabel(player),
                    onOff(now.follows(player)),
                    onOff(loaded.follows(player)),
                )
            }
    // Which AI service is on, before the services' models, as the settings show it.
    val provider = LOAD_ONLY_LINES.flatMap { it.lines(now, loaded, modelName) }
    return players +
        RESET_LINES.flatMap { line ->
            (if ("aiModels" in line.fields) provider else emptyList()) + line.lines(now, loaded, modelName)
        }
}

/** A line under *Not loaded:*: what of the file was not loaded, and why. */
@Composable
internal fun skippedLine(skipped: SettingsFile.Skipped): String =
    when (skipped) {
        is SettingsFile.Skipped.Unknown -> stringResource(R.string.settings_load_file_unknown, skipped.name)
        is SettingsFile.Skipped.Invalid -> {
            val title = fileTitle(skipped.field)?.let { stringResource(it) } ?: skipped.field
            val named =
                skipped.entry?.takeIf { it != skipped.value }?.let {
                    stringResource(R.string.settings_load_file_item, title, it)
                } ?: title
            stringResource(R.string.settings_load_file_invalid, named, skipped.value)
        }
        SettingsFile.Skipped.DeveloperModeOff -> stringResource(R.string.settings_load_file_developer)
        is SettingsFile.Skipped.ShortensHistory ->
            stringResource(
                R.string.settings_load_file_history,
                stringResource(R.string.settings_keep_history),
                lifetimeLabel(skipped.value),
                stringResource(R.string.settings_section_history),
            )
    }
