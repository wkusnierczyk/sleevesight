package app.sleevesight.app.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

// The history's settings (#148): *Keep history for*, *Start a new session after a break of*, and
// *Clear history*.

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
private const val MINUTES_IN_HOUR = 60

@Composable
internal fun HistorySection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    SectionHeading(R.string.settings_section_history)
    Hint(R.string.settings_history_hint)

    val lifetime = settings.historyLifetime
    val lifetimes =
        ((HISTORY_DAYS.map { it.days } + listOfNotNull(lifetime)).distinct().sorted() + null)
            .map { it to lifetimeLabel(it) }
    ValueRow(title = stringResource(R.string.settings_keep_history), value = lifetimeLabel(lifetime)) {
        open(
            SettingsDialog.Choice(R.string.settings_keep_history, lifetimes, lifetime) { chosen ->
                update { it.copy(historyLifetime = chosen) }
            },
        )
    }

    val gap = settings.sessionGap
    val gaps = (SESSION_GAP_MINUTES.map { it.minutes } + gap).distinct().sorted().map { it to gapLabel(it) }
    ValueRow(
        title = stringResource(R.string.settings_session_gap),
        value = gapLabel(gap),
        summary = stringResource(R.string.settings_session_gap_summary),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_session_gap, gaps, gap) { chosen ->
                update { it.copy(sessionGap = chosen) }
            },
        )
    }

    ValueRow(
        title = stringResource(R.string.settings_clear_history),
        value = stringResource(R.string.settings_clear_history_value),
        onClick = { open(SettingsDialog.ClearHistory) },
    )
}

/**
 * Confirms *Clear history*, which deletes the session history and the track changes. [onClear]
 * starts the clear; the dialog closes at once, and the screen says if it failed.
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

/** *Keep history for*: years when whole, else days; null is *Forever*. */
@Composable
private fun lifetimeLabel(lifetime: Duration?): String {
    val days = lifetime?.inWholeDays?.toInt() ?: return stringResource(R.string.settings_keep_history_forever)
    return if (days > 0 && days % DAYS_IN_YEAR == 0) {
        pluralStringResource(R.plurals.settings_years, days / DAYS_IN_YEAR, days / DAYS_IN_YEAR)
    } else {
        daysLabel(lifetime)
    }
}

/** A session gap: hours when whole, else minutes. */
@Composable
private fun gapLabel(gap: Duration): String {
    val minutes = gap.inWholeMinutes.toInt()
    return if (minutes > 0 && minutes % MINUTES_IN_HOUR == 0) {
        pluralStringResource(R.plurals.settings_hours, minutes / MINUTES_IN_HOUR, minutes / MINUTES_IN_HOUR)
    } else {
        pluralStringResource(R.plurals.settings_minutes, minutes, minutes)
    }
}
