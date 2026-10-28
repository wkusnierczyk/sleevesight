package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.Settings

// *Read aloud* (#325), at the end of *Insights*: a line on what reads an insight aloud, and who may
// hear its text, then *While reading aloud*, *Read the track line first* and *Read new insights aloud*.

/**
 * The read-aloud rows (#325), under a line ([Hint]) that says what *Read aloud* is and that the
 * phone's text-to-speech engine, the one chosen in Android's settings, speaks the text, which some
 * engines send to their server: *While reading aloud* (`readAloudMusic`), *Lower the music* or
 * *Pause the music*, a button group with what the chosen one does under it; *Read the track line
 * first* (`readTrackLineFirst`); and *Read new insights aloud* (`readNewInsightsAloud`). Each can be
 * changed whatever *Show insights* says: none of them calls the AI service, and a kept insight
 * can be read aloud.
 */
@Composable
internal fun ReadAloudRows(
    settings: Settings,
    update: Update,
) {
    val music = stringResource(R.string.settings_read_aloud_music)
    val trackLine = stringResource(R.string.settings_read_track_line)
    val newInsights = stringResource(R.string.settings_read_new_insights)
    Hint(R.string.settings_read_aloud_hint, over = listOf(music, trackLine, newInsights))
    ChoiceButtonsRow(
        title = music,
        summary = stringResource(R.string.settings_read_aloud_music_summary),
        choices = readAloudMusics(),
        selected = settings.readAloudMusic,
        details = readAloudMusicDetails(),
    ) { chosen -> update { it.copy(readAloudMusic = chosen) } }
    SwitchRow(
        title = trackLine,
        summary = stringResource(R.string.settings_read_track_line_summary),
        checked = settings.readTrackLineFirst,
    ) { on -> update { it.copy(readTrackLineFirst = on) } }
    SwitchRow(
        title = newInsights,
        summary = stringResource(R.string.settings_read_new_insights_summary),
        checked = settings.readNewInsightsAloud,
    ) { on -> update { it.copy(readNewInsightsAloud = on) } }
}

/** *While reading aloud*'s choices, in the order the row shows them. */
@Composable
internal fun readAloudMusics(): List<Pair<ReadAloudMusic, String>> =
    listOf(
        ReadAloudMusic.LOWER to stringResource(R.string.settings_read_aloud_lower),
        ReadAloudMusic.PAUSE to stringResource(R.string.settings_read_aloud_pause),
    )

/** What each of *While reading aloud*'s choices does, said under the row while it is chosen. */
@Composable
internal fun readAloudMusicDetails(): Map<ReadAloudMusic, String> =
    mapOf(
        ReadAloudMusic.LOWER to stringResource(R.string.settings_read_aloud_lower_detail),
        ReadAloudMusic.PAUSE to stringResource(R.string.settings_read_aloud_pause_detail),
    )

/** The read-aloud rows as the settings' search finds them (#649), for *Insights*' index. */
@Composable
internal fun readAloudIndexRows(settings: Settings): List<IndexedRow> {
    val musics = readAloudMusics()
    val details = readAloudMusicDetails()
    return listOf(
        choiceRow(
            stringResource(R.string.settings_read_aloud_music),
            stringResource(R.string.settings_read_aloud_music_summary),
            musics.first { it.first == settings.readAloudMusic }.second,
            // The line over the rows, found with the first of them, which it shows with.
            musics.map { it.second } + details.values + stringResource(R.string.settings_read_aloud_hint),
        ),
        indexRow(
            stringResource(R.string.settings_read_track_line),
            stringResource(R.string.settings_read_track_line_summary),
        ),
        indexRow(
            stringResource(R.string.settings_read_new_insights),
            stringResource(R.string.settings_read_new_insights_summary),
        ),
    )
}
