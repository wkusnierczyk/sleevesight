package app.sleevesight.app.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.LookUpAfterClear
import app.sleevesight.core.settings.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// *History* (#148, #444): *Keep history for*, then *Clear history* under it, so how long the history
// is kept and the way to wipe it sit together, since *Forever* is offered (#547), and under that
// *Look up the track again*, what follows a clear for the track on Home (#1112); *New session idle
// trigger*, how long each source's findings are kept (D9), and *Keep what sources said about each
// artist* (#420). A choice from a range is a stepped slider, an action a button (#1126).

private const val DAYS_IN_YEAR = 365
private val YEAR = DAYS_IN_YEAR.days

/** The cache lifetimes of the sources with an adapter (D9); Discogs's comes with it. */
internal val SHOWN_LIFETIMES =
    listOf(
        SourceId.MUSICBRAINZ to R.string.settings_source_musicbrainz,
        SourceId.WIKIDATA to R.string.settings_cache_wikidata,
        SourceId.WIKIPEDIA to R.string.settings_cache_wikipedia,
        SourceId.LASTFM to R.string.settings_source_lastfm,
        SourceId.LRCLIB to R.string.settings_source_lrclib,
        SourceId.WEB to R.string.settings_cache_web,
    )

@Composable
internal fun HistorySection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    KeepHistoryRow(settings, update)
    ButtonRow(
        title = stringResource(R.string.settings_clear_history),
        summary = stringResource(R.string.settings_clear_history_summary),
        label = stringResource(R.string.settings_clear),
        tag = CLEAR_HISTORY_BUTTON_TAG,
        destructive = true,
    ) { open(SettingsDialog.ClearHistory) }
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_look_up_again),
        summary = stringResource(R.string.settings_look_up_again_summary),
        choices = lookUpAgainChoices(),
        selected = settings.lookUpAfterClear,
    ) { chosen -> update { it.copy(lookUpAfterClear = chosen) } }

    // Where *Don't repeat artist facts* links to (#581).
    LandingSpot(SettingsTarget.sessionGap) {
        val title = stringResource(R.string.settings_session_gap)
        if (shownInSearch(title)) {
            val stops = sessionGapStops()
            SteppedRow(
                title = title,
                summary = { _, _ -> stringResource(R.string.settings_session_gap_summary) },
                stops = stops,
                stored = nearestStop(stops.values, settings.sessionGap),
                applies = true,
                tags = SteppedTags(SESSION_GAP_SLIDER_TAG, SESSION_GAP_STOPS_TAG, SESSION_GAP_VALUE_TAG),
                look = SteppedLook(start = 0.dp),
            ) { gap -> update { it.copy(sessionGap = gap) } }
        }
    }

    LifetimeRows(settings, update)

    // A privacy choice (#420), so its title is in the theme's error colour: what is kept per
    // artist amounts to a list of the artists heard. After the lifetimes it is kept for.
    SwitchRow(
        title = stringResource(R.string.settings_keep_artists),
        summary = stringResource(R.string.settings_keep_artists_summary),
        checked = settings.keepArtistResults,
        highlight = true,
    ) { on -> update { it.copy(keepArtistResults = on) } }
}

/**
 * *Keep history for* (#1126): a stepped slider, every stop labelled, *Forever* the last. A shorter
 * time deletes what is older at once and cannot be undone, so a move is stored only once the thumb
 * is let go, and a shorter time asks first ([ShortenHistoryDialog]); a longer one is stored at once.
 */
@Composable
private fun KeepHistoryRow(
    settings: Settings,
    update: Update,
) {
    val title = stringResource(R.string.settings_keep_history)
    if (!shownInSearch(title)) return
    SteppedRow(
        title = title,
        summary = { _, _ -> stringResource(R.string.settings_keep_history_summary) },
        stops = historyStops(),
        stored = nearestHistoryStop(settings.historyLifetime),
        applies = true,
        tags = SteppedTags(KEEP_HISTORY_SLIDER_TAG, KEEP_HISTORY_STOPS_TAG, KEEP_HISTORY_VALUE_TAG),
        look = SteppedLook(start = 0.dp),
        askBeforeLower =
            AskBeforeLower(settings.historyLifetime, ::shorter) { spoken, confirm, cancel ->
                ShortenHistoryDialog(spoken, confirm, cancel)
            },
    ) { chosen -> update { it.copy(historyLifetime = chosen) } }
}

/** Whether history kept for [lifetime] is kept shorter than for [than]; null is *Forever*. */
private fun shorter(
    lifetime: Duration?,
    than: Duration?,
): Boolean = lifetime != null && (than == null || lifetime < than)

/**
 * Asks before *Keep history for* is shortened to [time] (#1126), in words (*30 days*): [onShorten]
 * stores it, and what is older is deleted; [onCancel], or a tap outside, puts the thumb back.
 */
@Composable
internal fun ShortenHistoryDialog(
    time: String,
    onShorten: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_keep_history_shorten_title, time)) },
        text = {
            Text(
                stringResource(R.string.settings_keep_history_shorten_text, time),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onShorten,
            ) { Text(stringResource(R.string.settings_keep_history_shorten)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/**
 * How long each source's findings are kept (D9), a row per source with an adapter, each a stepped
 * slider labelled only at its ends (#1126), starting with the source's badge, in the column and
 * style of the badges under *Sources* (#670): Wikidata, kept apart from Wikipedia here, has its own
 * letter, `WD`.
 */
@Composable
private fun LifetimeRows(
    settings: Settings,
    update: Update,
) {
    // Over the rows under it, so a search that finds one of them shows it too (#649).
    val shown = SHOWN_LIFETIMES.filter { (source, _) -> settings.cacheLifetimes[source] != null }
    Hint(R.string.settings_cache_lifetimes, over = shown.map { (_, name) -> stringResource(name) })
    val stops = lifetimeStops()
    for ((source, name) in SHOWN_LIFETIMES) {
        val lifetime = settings.cacheLifetimes[source]
        val title = stringResource(name)
        if (lifetime == null || !shownInSearch(title)) continue
        SteppedRow(
            title = title,
            summary = null,
            stops = stops,
            stored = nearestStop(stops.values, lifetime),
            applies = true,
            tags = lifetimeTags(source),
            look = SteppedLook(start = 0.dp, leading = { ServiceBadge(badgeLetter(source)) }, endsOnly = true),
        ) { chosen -> update { it.copy(cacheLifetimes = it.cacheLifetimes + (source to chosen)) } }
    }
}

/**
 * Confirms *Clear history*, which deletes everything kept about the tracks played (#547). [onClear]
 * starts the clear; the dialog closes at once, and the screen says how it ended.
 */
@Composable
internal fun ClearHistoryDialog(
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_history_title)) },
        text = {
            Text(stringResource(R.string.settings_clear_history_explained), style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClear()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.settings_clear)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** *Look up the track again*'s choices (#1112), in the group's order. */
@Composable
internal fun lookUpAgainChoices(): List<Pair<LookUpAfterClear, String>> =
    listOf(
        LookUpAfterClear.AUTOMATICALLY to stringResource(R.string.settings_look_up_again_automatically),
        LookUpAfterClear.ON_REQUEST to stringResource(R.string.settings_look_up_again_on_request),
    )

/** *Keep history for*: null is *Forever*. */
@Composable
internal fun lifetimeLabel(lifetime: Duration?): String =
    lifetime?.let { durationLabel(it) } ?: stringResource(R.string.settings_keep_history_forever)

/**
 * [duration] in the largest unit it is a whole number of: years of 365 days, days, hours, minutes,
 * else seconds.
 */
@Composable
internal fun durationLabel(duration: Duration): String {
    val (unit, count) =
        when {
            duration.isWhole(YEAR) -> R.plurals.settings_years to duration / YEAR
            duration.isWhole(1.days) -> R.plurals.settings_days to duration / 1.days
            duration.isWhole(1.hours) -> R.plurals.settings_hours to duration / 1.hours
            duration.isWhole(1.minutes) -> R.plurals.settings_minutes to duration / 1.minutes
            else -> R.plurals.settings_seconds to duration / 1.seconds
        }
    val whole = count.toInt()
    return pluralStringResource(unit, whole, whole)
}

private fun Duration.isWhole(unit: Duration): Boolean =
    this >= unit && inWholeMilliseconds % unit.inWholeMilliseconds == 0L
