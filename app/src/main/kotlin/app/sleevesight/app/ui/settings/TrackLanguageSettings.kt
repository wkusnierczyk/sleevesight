package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.TrackLanguageSearch

// *Sources* → *Search in the track’s language* (#902), after *Exa web search*.

/**
 * *Search in the track’s language* (`trackLanguageSearch`, #902): *Off*, *If needed* or *Always*, a
 * button group, and what the chosen value does under it. Each lookup in the language obeys its own
 * source's switch (Wikipedia's, Exa web search's), so the row itself is never disabled.
 */
@Composable
internal fun TrackLanguageRow(
    settings: Settings,
    update: Update,
) {
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_track_language),
        summary = stringResource(R.string.settings_track_language_summary),
        choices = TRACK_LANGUAGE_CHOICES.map { (value, label, _) -> value to stringResource(label) },
        selected = settings.trackLanguageSearch,
        details = TRACK_LANGUAGE_CHOICES.associate { (value, _, detail) -> value to stringResource(detail) },
    ) { chosen -> update { it.copy(trackLanguageSearch = chosen) } }
}

// *Search in the track’s language*'s values, in the group's order, each with its label and what it does.
private val TRACK_LANGUAGE_CHOICES: List<Triple<TrackLanguageSearch, Int, Int>> =
    listOf(
        Triple(
            TrackLanguageSearch.OFF,
            R.string.settings_track_language_off,
            R.string.settings_track_language_off_detail,
        ),
        Triple(
            TrackLanguageSearch.IF_NEEDED,
            R.string.settings_track_language_if_needed,
            R.string.settings_track_language_if_needed_detail,
        ),
        Triple(
            TrackLanguageSearch.ALWAYS,
            R.string.settings_track_language_always,
            R.string.settings_track_language_always_detail,
        ),
    )

/**
 * *Search in the track’s language* (#902) as the settings' search finds it: its name, description,
 * and choices with what each does.
 */
@Composable
internal fun trackLanguageIndexRow(): IndexedRow {
    val title = stringResource(R.string.settings_track_language)
    val choices =
        TRACK_LANGUAGE_CHOICES.flatMap { (_, label, detail) -> listOf(stringResource(label), stringResource(detail)) }
    return IndexedRow(title, listOf(title, stringResource(R.string.settings_track_language_summary)) + choices)
}
