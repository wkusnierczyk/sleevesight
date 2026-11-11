package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.Settings

// *Read aloud* (#325), at the end of *Insights*: its heading, drawn as a setting's title, with
// a description of what reads an insight aloud and who may hear its text (#946), then *Music while
// reading aloud*, *Read the track line first* and *Read new insights aloud*.

/**
 * The read-aloud rows (#325), under the heading *Read aloud* ([HeadingRow], #946), whose
 * description says that the speaker button has the phone's text-to-speech engine, the one chosen
 * in Android's settings, speak the text, which some engines send to their server: *Music while
 * reading aloud* (`readAloudMusic`), *Lower* or *Pause*, a button group, with what the chosen one
 * does under it; *Read the track line first* (`readTrackLineFirst`); and *Read new insights aloud*
 * (`readNewInsightsAloud`). Each can be changed whatever *Show insights* says: none of them calls
 * the AI service, and a kept insight can be read aloud.
 */
@Composable
internal fun ReadAloudRows(
    settings: Settings,
    update: Update,
) {
    val music = stringResource(R.string.settings_read_aloud_music)
    val trackLine = stringResource(R.string.settings_read_track_line)
    val newInsights = stringResource(R.string.settings_read_new_insights)
    HeadingRow(
        title = stringResource(R.string.settings_read_aloud),
        summary = stringResource(R.string.settings_read_aloud_summary),
        over = listOf(music, trackLine, newInsights),
    )
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

/** A row titled [title], found by it, [summary] and the [heading] over it. */
private fun headed(
    title: String,
    summary: String,
    heading: List<String>,
): IndexedRow = IndexedRow(title, listOf(title, summary) + heading)

/** *Music while reading aloud*'s choices, in the order the row shows them. */
@Composable
internal fun readAloudMusics(): List<Pair<ReadAloudMusic, String>> =
    listOf(
        ReadAloudMusic.LOWER to stringResource(R.string.settings_read_aloud_lower),
        ReadAloudMusic.PAUSE to stringResource(R.string.settings_read_aloud_pause),
    )

/** What each of *Music while reading aloud*'s choices does, said under the row while it is chosen. */
@Composable
internal fun readAloudMusicDetails(): Map<ReadAloudMusic, String> =
    mapOf(
        ReadAloudMusic.LOWER to stringResource(R.string.settings_read_aloud_lower_detail),
        ReadAloudMusic.PAUSE to stringResource(R.string.settings_read_aloud_pause_detail),
    )

/**
 * The read-aloud rows as the settings' search finds them (#649), for *Insights*' index. The heading
 * over them and its description (#946) are found with each of them, so a search for either shows
 * the heading with all three rows under it.
 */
@Composable
internal fun readAloudIndexRows(settings: Settings): List<IndexedRow> {
    val musics = readAloudMusics()
    val details = readAloudMusicDetails()
    val heading =
        listOf(stringResource(R.string.settings_read_aloud), stringResource(R.string.settings_read_aloud_summary))
    return listOf(
        choiceRow(
            stringResource(R.string.settings_read_aloud_music),
            stringResource(R.string.settings_read_aloud_music_summary),
            musics.first { it.first == settings.readAloudMusic }.second,
            musics.map { it.second } + details.values + heading,
        ),
        headed(
            stringResource(R.string.settings_read_track_line),
            stringResource(R.string.settings_read_track_line_summary),
            heading,
        ),
        headed(
            stringResource(R.string.settings_read_new_insights),
            stringResource(R.string.settings_read_new_insights_summary),
            heading,
        ),
    )
}
