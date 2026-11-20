package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.settings.Keyless
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/**
 * What a settings file holds of the settings with several items (#815): the AI services, source
 * switches and findings' lifetimes the settings show, so each one a load changes has its line.
 */
internal val APP_OFFERED: SettingsFile.Offered =
    SettingsFile.Offered(
        providers = OFFERED_PROVIDERS.toSet(),
        sourceSwitches = OFFERED_SOURCE_SWITCHES,
        cacheSources = SHOWN_LIFETIMES.map { it.first }.toSet(),
    )

/** The fields whose value is a time shown as a slider's stop (*15 s*, *1 min*). */
private val STOP_FIELDS = setOf("musicBrainzWait", "aiRetry")

/** The fields whose value is a time shown in words (*2 hours*, *30 days*). */
private val TIME_FIELDS = setOf("sessionGap", "historyLifetime", "cacheLifetimes")

/**
 * [value], as a file has it for [field], shown as the settings show such a value where it is a time
 * in whole seconds (*90 s*, *2 hours*); else as in the file.
 */
@Composable
private fun fileValueLabel(
    field: String,
    value: String,
): String {
    val time = Duration.parseOrNull(value)?.takeIf { it.isPositive() && it == it.inWholeSeconds.seconds }
    return when {
        time == null -> value
        field in STOP_FIELDS -> stopLabel(time, value)
        field == "crossfade" -> crossfadeLabel(Crossfade.Fixed(time))
        field in TIME_FIELDS -> durationLabel(time)
        else -> value
    }
}

/** A line under *Not loaded:* for a service the file switches on that stays off for want of its key. */
@Composable
internal fun keylessLine(keyless: Keyless): String {
    val name =
        when (keyless) {
            is Keyless.Provider -> providerName(keyless.provider)
            is Keyless.Source -> SOURCE_ROW_NAMES[keyless.switch]
            is Keyless.Engine -> SOURCE_ROW_NAMES[keyless.engine]
        }
    val fallback =
        when (keyless) {
            is Keyless.Provider -> keyless.provider.name
            is Keyless.Source -> keyless.switch.name
            is Keyless.Engine -> keyless.engine.name
        }
    return stringResource(R.string.settings_load_file_no_key, name?.let { stringResource(it) } ?: fallback)
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
            stringResource(R.string.settings_load_file_invalid, named, fileValueLabel(skipped.field, skipped.value))
        }
        is SettingsFile.Skipped.TooMany ->
            stringResource(
                R.string.settings_load_file_too_many,
                fileTitle(skipped.field)?.let { stringResource(it) } ?: skipped.field,
                skipped.count,
            )
        SettingsFile.Skipped.DeveloperModeOff -> stringResource(R.string.settings_load_file_developer)
        is SettingsFile.Skipped.ShortensHistory ->
            stringResource(
                R.string.settings_load_file_history,
                stringResource(R.string.settings_keep_history),
                lifetimeLabel(skipped.value),
                stringResource(R.string.settings_section_history),
            )
    }
