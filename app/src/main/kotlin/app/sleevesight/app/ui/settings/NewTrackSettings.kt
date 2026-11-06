package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings

/**
 * *Sources on a new track* (#269; the switch *Keep Sources as you left them* until #522) and *Song,
 * Artist and Album on a new track* (#522), after *Layout*: how the sources and the three insights
 * under *Key insights* start on each new track, *Folded* (the default), *Unfolded* or *As left*, as
 * button groups. Under *Expandable boxes* only: under *Separate pages*, where each page opens
 * fresh, they do not apply, so they show dimmed as a whole and cannot be changed (#999).
 */
@Composable
internal fun NewTrackRows(
    settings: Settings,
    update: Update,
) {
    val boxes = settings.layout == Layout.EXPANDABLE_BOXES
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_sources_on_new_track),
        summary = stringResource(R.string.settings_sources_on_new_track_summary),
        choices = newTrackFolds(),
        selected = settings.sourcesOnNewTrack,
        applies = boxes,
    ) { choice -> if (boxes) update { it.copy(sourcesOnNewTrack = choice) } }
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_items_on_new_track),
        summary = stringResource(R.string.settings_items_on_new_track_summary),
        choices = newTrackFolds(),
        selected = settings.itemsOnNewTrack,
        applies = boxes,
    ) { choice -> if (boxes) update { it.copy(itemsOnNewTrack = choice) } }
}

/** The choices of *Sources on a new track* and *Song, Artist and Album on a new track* (#522). */
@Composable
internal fun newTrackFolds(): List<Pair<NewTrackFold, String>> =
    listOf(
        NewTrackFold.FOLDED to stringResource(R.string.settings_new_track_folded),
        NewTrackFold.UNFOLDED to stringResource(R.string.settings_new_track_unfolded),
        NewTrackFold.AS_LEFT to stringResource(R.string.settings_new_track_as_left),
    )
