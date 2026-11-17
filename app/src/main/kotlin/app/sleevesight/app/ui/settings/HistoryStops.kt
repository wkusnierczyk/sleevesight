package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.model.SourceId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

// *History*'s stepped sliders (#1126): their stops and test tags.

/** The test tag of *Keep history for*'s slider (#1126). */
internal const val KEEP_HISTORY_SLIDER_TAG = "settings-keep-history-slider"

/** The test tag of the labels under *Keep history for*'s stops. */
internal const val KEEP_HISTORY_STOPS_TAG = "settings-keep-history-stops"

/** The test tag of *Keep history for*'s value, at the row's right. */
internal const val KEEP_HISTORY_VALUE_TAG = "settings-keep-history-value"

/** The test tag of *New session idle trigger*'s slider (#1126). */
internal const val SESSION_GAP_SLIDER_TAG = "settings-session-gap-slider"

/** The test tag of the labels under *New session idle trigger*'s stops. */
internal const val SESSION_GAP_STOPS_TAG = "settings-session-gap-stops"

/** The test tag of *New session idle trigger*'s value, at the row's right. */
internal const val SESSION_GAP_VALUE_TAG = "settings-session-gap-value"

/** The test tag of *Clear history*'s button (#1126). */
internal const val CLEAR_HISTORY_BUTTON_TAG = "settings-clear-history-button"

/** The test tags of a source's lifetime's slider, its stops' labels and its value, by the source (#1126). */
internal fun lifetimeTags(source: SourceId): SteppedTags =
    SteppedTags(
        "settings-lifetime-slider-${source.name}",
        "settings-lifetime-stops-${source.name}",
        "settings-lifetime-value-${source.name}",
    )

/**
 * The history's lifetimes offered, in days, then *Forever*. A week is the least: a shorter one
 * would empty the track list of someone who listens once a week, and leave skip detection little to
 * learn from (D15). A year is the most short of keeping it for ever.
 */
@Suppress("MagicNumber") // The values are the offer itself.
private val HISTORY_DAYS = listOf(7, 30, 90, 365)

/**
 * The session gaps offered, in minutes. Less than 10 minutes splits a session at a pause to answer
 * the door; more than 2 hours joins an afternoon's listening to the evening's.
 */
@Suppress("MagicNumber") // The values are the offer itself.
private val SESSION_GAP_MINUTES = listOf(10, 20, 30, 60, 120)

private const val MINUTES_IN_HOUR = 60L

/** The lifetimes offered, which include every default (D9). */
@Suppress("MagicNumber") // The values are the offer itself.
private val LIFETIME_DAYS = listOf(1, 3, 7, 14, 30, 90)

/**
 * *Keep history for*'s stops (#1126): 7, 30 and 90 days, 1 year, then *Forever* (null), each read
 * as labelled.
 */
@Composable
internal fun historyStops(): Stops<Duration?> {
    val values = HISTORY_DAYS.map { it.days } + null
    val labels = values.map { lifetimeLabel(it) }
    return Stops(values, labels, labels)
}

/** The index of *Keep history for*'s stop nearest [lifetime]: *Forever*'s for null, else the nearest time's. */
internal fun nearestHistoryStop(lifetime: Duration?): Int =
    lifetime?.let { nearestStop(HISTORY_DAYS.map { it.days }, it) } ?: HISTORY_DAYS.size

/**
 * *New session idle trigger*'s stops (#1126): 10, 20 and 30 minutes, 1 and 2 hours, labelled short
 * (*10 min*, *1 h*) and read in words (*10 minutes*, *1 hour*).
 */
@Composable
internal fun sessionGapStops(): Stops<Duration> {
    val values = SESSION_GAP_MINUTES.map { it.minutes }
    val labels =
        values.map {
            if (it.inWholeMinutes % MINUTES_IN_HOUR == 0L) {
                stringResource(R.string.settings_hours_short, it.inWholeHours.toInt())
            } else {
                stringResource(R.string.settings_musicbrainz_wait_minutes, it.inWholeMinutes.toInt())
            }
        }
    return Stops(values, labels, values.map { durationLabel(it) })
}

/** A source's lifetime's stops (D9, #1126), in days, each read as labelled. */
@Composable
internal fun lifetimeStops(): Stops<Duration> {
    val values = LIFETIME_DAYS.map { it.days }
    val labels = values.map { daysLabel(it) }
    return Stops(values, labels, labels)
}

/** *Keep history for* as last asked for through [writer], else [stored]; null is *Forever*. */
internal fun held(
    writer: HistoryLifetimeWriter?,
    stored: Duration?,
): Duration? = if (writer != null) writer.held(stored) else stored

/** Whether history kept for [lifetime] is kept shorter than for [than]; null is *Forever*. */
internal fun shorter(
    lifetime: Duration?,
    than: Duration?,
): Boolean = lifetime != null && (than == null || lifetime < than)
