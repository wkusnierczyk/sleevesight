package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// *Sources* → *Wait when busy* (#1120), under MusicBrainz, a stepped slider ([SteppedRow], shared
// with *AI services* → *Retry when the AI fails*, #1164, and *History*, #1126); and the labels of
// stops of a time.

/** The test tag of *Wait when busy*'s slider. */
internal const val MUSICBRAINZ_WAIT_SLIDER_TAG = "settings-musicbrainz-wait-slider"

/** The test tag of the labels under *Wait when busy*'s stops. */
internal const val MUSICBRAINZ_WAIT_STOPS_TAG = "settings-musicbrainz-wait-stops"

/** The test tag of *Wait when busy*'s value, at the row's right. */
internal const val MUSICBRAINZ_WAIT_VALUE_TAG = "settings-musicbrainz-wait-value"

/**
 * *Wait when busy* (`Settings.musicBrainzWait`, #1120), under MusicBrainz, started at its name:
 * how long a request MusicBrainz refuses as busy is asked again before the lookup goes on without
 * it. A stepped slider ([SteppedRow]), one stop for each of [Settings.MUSICBRAINZ_WAITS]. While
 * MusicBrainz is off the setting does not apply: the whole row dims and cannot be moved (#999).
 */
@Composable
internal fun MusicBrainzWaitRow(
    settings: Settings,
    update: Update,
) {
    val title = stringResource(R.string.settings_musicbrainz_wait)
    if (!shownInSearch(title)) return
    val none = stringResource(R.string.settings_musicbrainz_wait_none)
    val stops = durationStops(Settings.MUSICBRAINZ_WAITS, none)
    SteppedRow(
        title = title,
        summary = { _, _ -> stringResource(R.string.settings_musicbrainz_wait_summary) },
        stops = stops,
        stored = nearestStop(Settings.MUSICBRAINZ_WAITS, settings.musicBrainzWait),
        applies = SourceSwitch.MUSICBRAINZ in settings.enabledSources,
        tags = SteppedTags(MUSICBRAINZ_WAIT_SLIDER_TAG, MUSICBRAINZ_WAIT_STOPS_TAG, MUSICBRAINZ_WAIT_VALUE_TAG),
        choose = { wait -> update { it.copy(musicBrainzWait = wait) } },
    )
}

/** A stop's label, short to fit under the slider: [none] for zero, *15 s*, *1 min*. */
@Composable
internal fun stopLabel(
    stop: Duration,
    none: String,
): String =
    when {
        stop == Duration.ZERO -> none
        stop >= 1.minutes && stop == stop.inWholeMinutes.minutes ->
            stringResource(R.string.settings_musicbrainz_wait_minutes, stop.inWholeMinutes.toInt())
        else -> stringResource(R.string.settings_musicbrainz_wait_seconds, stop.inWholeSeconds.toInt())
    }

/**
 * The stops of a time on a [SteppedRow] (#1120, #1164): each labelled as [stopLabel] does, and read
 * by a screen reader in words, [none] for zero, else *15 seconds*, *1 minute* ([durationLabel]).
 */
@Composable
internal fun durationStops(
    stops: List<Duration>,
    none: String,
): Stops<Duration> =
    Stops(
        stops,
        stops.map { stopLabel(it, none) },
        stops.map { if (it == Duration.ZERO) none else durationLabel(it) },
    )

/**
 * *Wait when busy* (#1120) as the settings' search finds it: its name, description and the stops'
 * labels.
 */
@Composable
internal fun musicBrainzWaitIndexRow(): IndexedRow {
    val title = stringResource(R.string.settings_musicbrainz_wait)
    val summary = stringResource(R.string.settings_musicbrainz_wait_summary)
    val none = stringResource(R.string.settings_musicbrainz_wait_none)
    return IndexedRow(title, listOf(title, summary) + Settings.MUSICBRAINZ_WAITS.map { stopLabel(it, none) })
}
