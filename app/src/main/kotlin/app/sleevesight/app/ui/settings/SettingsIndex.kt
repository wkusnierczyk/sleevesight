package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

// What the settings' search looks through (#649): every section and its settings, as the screen
// names them.

/**
 * The settings' search index (#649): each section shown, with its name and descriptions, and each
 * of its settings by the title its row shows, with its description, its current value and the
 * choices it offers (*dark* finds *Theme*). Built from the string resources, so in the app's
 * language; the same rows as the sections draw for [settings] (*Crossfade* only under *On track
 * skip (auto)*, *Developer* only in developer mode), with the players [players] lists by name and
 * the AI services' models by name ([modelName]), as their rows show them, with the models found
 * unavailable ([unavailable], #540) said so as their rows say it. [keys] says whether
 * *Purge keys* shows, and each key's state.
 */
@Composable
internal fun settingsIndex(
    settings: Settings,
    keys: KeysShown,
    players: List<String>,
    modelName: (String) -> String,
    unavailable: Map<ProviderId, String> = emptyMap(),
): List<IndexedSection> =
    listOfNotNull(
        indexed(SettingsSection.PLAYERS, players.map { indexRow(it) }),
        indexed(SettingsSection.SOURCES, sourceIndexRows()),
        indexed(SettingsSection.AI_SERVICES, aiRows(settings, modelName, unavailable)),
        indexed(SettingsSection.SECRETS, secretRows(keys)),
        indexed(SettingsSection.GENRES, genreRows()),
        indexed(SettingsSection.INSIGHTS, insightRows(settings)),
        indexed(SettingsSection.HISTORY, historyRows(settings)),
        indexed(SettingsSection.APPEARANCE, appearanceRows()),
        indexed(SettingsSection.ABOUT, aboutRows()),
        if (settings.developerMode) indexed(SettingsSection.DEVELOPER, developerRows(settings)) else null,
    )

/** [section] with its name and descriptions, and [rows]. */
@Composable
private fun indexed(
    section: SettingsSection,
    rows: List<IndexedRow>,
): IndexedSection =
    IndexedSection(
        section,
        listOfNotNull(
            stringResource(section.title),
            section.summary?.let { stringResource(it) },
            section.details?.let { stringResource(it) },
        ),
        rows,
    )

/** A row titled [title], found by it and by [texts]. */
internal fun indexRow(
    title: String,
    vararg texts: String?,
): IndexedRow = IndexedRow(title, listOf(title) + texts.filterNotNull())

/** A row titled [title], found by it, [summary], [value], and its [choices] (with what each does). */
internal fun choiceRow(
    title: String,
    summary: String?,
    value: String,
    choices: Collection<String>,
): IndexedRow = IndexedRow(title, listOfNotNull(title, summary, value) + choices)
