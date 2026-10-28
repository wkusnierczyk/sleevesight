package app.sleevesight.app.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// *History* (#148, #444): *Keep history for*, then *Clear history* under it, so how long the history
// is kept and the way to wipe it sit together, since *Forever* is offered (#547); *New session idle
// trigger*, how long each source's findings are kept (D9), and *Keep what sources said about each
// artist* (#420).

/**
 * The history's lifetimes offered, in days, and *Forever*; a stored one outside them is offered
 * too. A week is the least: a shorter one would empty the track list of someone who listens once
 * a week, and leave skip detection little to learn from (D15). A year is the most short of
 * keeping it for ever.
 */
@Suppress("MagicNumber") // The values are the offer itself.
private val HISTORY_DAYS = listOf(7, 30, 90, 365)

/**
 * The session gaps offered, in minutes; a stored one outside them is offered too. Less than 10
 * minutes splits a session at a pause to answer the door; more than 2 hours joins an afternoon's
 * listening to the evening's.
 */
@Suppress("MagicNumber") // The values are the offer itself.
private val SESSION_GAP_MINUTES = listOf(10, 20, 30, 60, 120)

private const val DAYS_IN_YEAR = 365
private val YEAR = DAYS_IN_YEAR.days

/** The cache lifetimes of the sources with an adapter (D9); Discogs's comes with it. */
internal val SHOWN_LIFETIMES =
    listOf(
        SourceId.MUSICBRAINZ to R.string.settings_source_musicbrainz,
        SourceId.WIKIDATA to R.string.settings_cache_wikidata,
        SourceId.WIKIPEDIA to R.string.settings_cache_wikipedia,
        SourceId.LASTFM to R.string.settings_source_lastfm,
        SourceId.WEB to R.string.settings_cache_web,
    )

/** The lifetimes offered, which include every default (D9); a stored one outside them is offered too. */
@Suppress("MagicNumber") // The values are the offer itself.
private val LIFETIME_DAYS = listOf(1, 3, 7, 14, 30, 90)

@Composable
internal fun HistorySection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val lifetime = settings.historyLifetime
    val lifetimes = historyLifetimes(lifetime)
    ValueRow(title = stringResource(R.string.settings_keep_history), value = lifetimeLabel(lifetime)) {
        open(
            SettingsDialog.Choice(R.string.settings_keep_history, lifetimes, lifetime) { chosen ->
                update { it.copy(historyLifetime = chosen) }
            },
        )
    }
    ValueRow(
        title = stringResource(R.string.settings_clear_history),
        value = stringResource(R.string.settings_clear_history_value),
        onClick = { open(SettingsDialog.ClearHistory) },
    )

    val gap = settings.sessionGap
    val gaps = sessionGaps(gap)
    // Where *Don't repeat artist facts* links to (#581).
    LandingSpot(SettingsTarget.sessionGap) {
        ValueRow(
            title = stringResource(R.string.settings_session_gap),
            value = durationLabel(gap),
            summary = stringResource(R.string.settings_session_gap_summary),
        ) {
            open(
                SettingsDialog.Choice(R.string.settings_session_gap, gaps, gap) { chosen ->
                    update { it.copy(sessionGap = chosen) }
                },
            )
        }
    }

    LifetimeRows(settings, update, open)

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
 * How long each source's findings are kept (D9), a row per source with an adapter, each starting
 * with the source's badge, in the column and style of the badges under *Sources* (#670): Wikidata,
 * kept apart from Wikipedia here, has its own letter, `WD`.
 */
@Composable
private fun LifetimeRows(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    // Over the rows under it, so a search that finds one of them shows it too (#649).
    val shown = SHOWN_LIFETIMES.filter { (source, _) -> settings.cacheLifetimes[source] != null }
    Hint(R.string.settings_cache_lifetimes, over = shown.map { (_, name) -> stringResource(name) })
    for ((source, name) in SHOWN_LIFETIMES) {
        val lifetime = settings.cacheLifetimes[source] ?: continue
        val options = cacheLifetimes(lifetime)
        ValueRow(
            title = stringResource(name),
            value = daysLabel(lifetime),
            leading = { ServiceBadge(badgeLetter(source)) },
        ) {
            open(
                SettingsDialog.Choice(name, options, lifetime) { chosen ->
                    update { it.copy(cacheLifetimes = it.cacheLifetimes + (source to chosen)) }
                },
            )
        }
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

/** *Keep history for*'s choices, [lifetime] the one set among them; null is *Forever*. */
@Composable
internal fun historyLifetimes(lifetime: Duration?): List<Pair<Duration?, String>> {
    val lifetimes = (HISTORY_DAYS.map { it.days } + listOfNotNull(lifetime)).distinct().sorted()
    return (lifetimes + null).map { it to lifetimeLabel(it) }
}

/** *New session idle trigger*'s choices, [gap] the one set among them. */
@Composable
internal fun sessionGaps(gap: Duration): List<Pair<Duration, String>> =
    (SESSION_GAP_MINUTES.map { it.minutes } + gap).distinct().sorted().map { it to durationLabel(it) }

/** A source's cache lifetime's choices, [lifetime] the one set among them. */
@Composable
internal fun cacheLifetimes(lifetime: Duration): List<Pair<Duration, String>> =
    (LIFETIME_DAYS.map { it.days } + lifetime).distinct().sorted().map { it to daysLabel(it) }

/** *Keep history for*: null is *Forever*. */
@Composable
internal fun lifetimeLabel(lifetime: Duration?): String =
    lifetime?.let { durationLabel(it) } ?: stringResource(R.string.settings_keep_history_forever)

/**
 * [duration] in the largest unit it is a whole number of: years of 365 days, days, hours, minutes,
 * else seconds. The options are all whole; a stored value outside them, as from a later version,
 * is shown as it is.
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
