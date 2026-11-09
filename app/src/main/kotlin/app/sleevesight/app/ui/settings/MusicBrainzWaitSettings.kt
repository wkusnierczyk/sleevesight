package app.sleevesight.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// *Sources* → *Wait when busy* (#1120), under MusicBrainz.

/** The test tag of *Wait when busy*'s slider. */
internal const val MUSICBRAINZ_WAIT_SLIDER_TAG = "settings-musicbrainz-wait-slider"

/** The test tag of the labels under *Wait when busy*'s stops. */
internal const val MUSICBRAINZ_WAIT_STOPS_TAG = "settings-musicbrainz-wait-stops"

/** The test tag of *Wait when busy*'s value, at the row's right. */
internal const val MUSICBRAINZ_WAIT_VALUE_TAG = "settings-musicbrainz-wait-value"

private const val DIMMED_ALPHA = 0.38f

/** The slider's thumb: a circle this wide, whose half the track is inset by at each end. */
private val THUMB = 24.dp

private val TRACK = 4.dp
private val STOP = 10.dp

/** The least room between two labels under the stops. */
private val LABEL_GAP = 8.dp

/**
 * *Wait when busy* (`Settings.musicBrainzWait`, #1120), under MusicBrainz, started at its name:
 * how long a request MusicBrainz refuses as busy is asked again before the lookup goes on without
 * it. A stepped slider, one stop for each of [Settings.MUSICBRAINZ_WAITS], each with its label under
 * it ([StopLabels]), and the value chosen at the row's right, beside the name; a move is stored at
 * once. A screen reader reads the slider by the row's name, with the value in words. While
 * MusicBrainz is off the setting does not apply: the whole row dims and cannot be moved (#999).
 * The slider has a thumb and a track of its own ([Thumb], [StopsTrack]), as the picked design draws them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicBrainzWaitRow(
    settings: Settings,
    update: Update,
) {
    val title = stringResource(R.string.settings_musicbrainz_wait)
    if (!shownInSearch(title)) return
    val applies = SourceSwitch.MUSICBRAINZ in settings.enabledSources
    val alpha = if (applies) 1f else DIMMED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val waits = Settings.MUSICBRAINZ_WAITS
    val stored = nearestStop(settings.musicBrainzWait)
    // Follows a drag at once; the stored value catches up as it is written.
    var shown by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    val index = shown.roundToInt().coerceIn(waits.indices)
    val labels = waits.map { waitLabel(it) }
    val spoken = waitSpoken(waits[index])
    Column(Modifier.fillMaxWidth().padding(start = UNDER_BADGE, top = 8.dp, bottom = 8.dp)) {
        Row {
            Text(
                searchMarked(title),
                modifier = Modifier.weight(1f).testTag(ROW_TITLE_TAG),
                style = MaterialTheme.typography.bodyLarge,
                color = color,
            )
            Spacer(Modifier.width(16.dp))
            // Read with the slider, not on its own.
            Text(
                labels[index],
                modifier = Modifier.testTag(MUSICBRAINZ_WAIT_VALUE_TAG).semantics { hideFromAccessibility() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Description(title, stringResource(R.string.settings_musicbrainz_wait_summary), color = color)
        Slider(
            value = shown,
            onValueChange = { value ->
                shown = value
                val chosen = value.roundToInt().coerceIn(waits.indices)
                if (chosen != stored) update { it.copy(musicBrainzWait = waits[chosen]) }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Dimmed as one layer, so the track does not show through the thumb.
                    .alpha(alpha)
                    .testTag(MUSICBRAINZ_WAIT_SLIDER_TAG)
                    .semantics {
                        contentDescription = title
                        stateDescription = spoken
                    },
            enabled = applies,
            valueRange = 0f..waits.lastIndex.toFloat(),
            steps = waits.size - 2,
            thumb = { Thumb() },
            track = { state -> StopsTrack(state, waits.size) },
        )
        StopLabels(labels, color)
    }
}

/** The index of the stop nearest [wait]: a value stored that is not a stop shows at the nearest. */
private fun nearestStop(wait: Duration): Int =
    Settings.MUSICBRAINZ_WAITS.indices.minBy { abs((Settings.MUSICBRAINZ_WAITS[it] - wait).inWholeMilliseconds) }

/** A stop's label, short to fit under the slider: *Don’t wait*, *15 s*, *1 min*. */
@Composable
private fun waitLabel(wait: Duration): String =
    when {
        wait == Duration.ZERO -> stringResource(R.string.settings_musicbrainz_wait_none)
        wait >= 1.minutes && wait == wait.inWholeMinutes.minutes ->
            stringResource(R.string.settings_musicbrainz_wait_minutes, wait.inWholeMinutes.toInt())
        else -> stringResource(R.string.settings_musicbrainz_wait_seconds, wait.inWholeSeconds.toInt())
    }

/** A stop as a screen reader says it, in words: *Don’t wait*, *15 seconds*, *1 minute*. */
@Composable
private fun waitSpoken(wait: Duration): String =
    if (wait == Duration.ZERO) stringResource(R.string.settings_musicbrainz_wait_none) else durationLabel(wait)

/** The slider's thumb, a circle in the theme's primary colour. */
@Composable
private fun Thumb() {
    Box(
        Modifier
            .size(THUMB)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

/**
 * The slider's track: a line, in the primary colour up to the thumb, with a dot at each of its
 * [stops], filled up to the thumb. Laid out by the slider between the thumb's middles at either end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopsTrack(
    state: SliderState,
    stops: Int,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    val range = state.valueRange
    val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(THUMB)) {
        val y = size.height / 2
        val stroke = TRACK.toPx()
        val reached = size.width * fraction
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        drawLine(active, Offset(0f, y), Offset(reached, y), stroke, StrokeCap.Round)
        for (stop in 0 until stops) {
            val x = size.width * stop / (stops - 1)
            drawCircle(if (x <= reached + 1f) active else inactive, STOP.toPx() / 2, Offset(x, y))
        }
    }
}

/**
 * The [labels] under the stops, each centred under its stop as far as the row's edges and its
 * neighbours allow: none overlaps another, or leaves the row, at any font size. Each takes one line
 * while all fit side by side; where they do not, the widest wrap between their words, one at a time,
 * until they fit ([labelWidths]), and the labels then move off their stops only as far as they must
 * ([labelPositions]). Read with the slider, not on their own.
 */
@Composable
private fun StopLabels(
    labels: List<String>,
    color: Color,
) {
    val style = MaterialTheme.typography.bodySmall
    // Each label's longest word, measured as drawn: the intrinsic width's own line breaker may break
    // inside one (after an apostrophe, as the JVM's does).
    val measurer = rememberTextMeasurer()
    Layout(
        content = {
            labels.forEachIndexed { i, label ->
                val align =
                    when (i) {
                        0 -> TextAlign.Start
                        labels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    }
                Text(
                    label,
                    modifier = Modifier.semantics { hideFromAccessibility() },
                    style = style,
                    color = color,
                    textAlign = align,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(MUSICBRAINZ_WAIT_STOPS_TAG),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val inset = THUMB.toPx() / 2
        val gap = LABEL_GAP.roundToPx()
        val last = measurables.lastIndex
        val widths =
            labelWidths(
                measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) },
                labels.map { label -> label.split(' ').maxOf { measurer.measure(it, style).size.width } + 1 },
                width - gap * last,
            )
        val placeables = measurables.zip(widths) { m, w -> m.measure(Constraints(maxWidth = w.coerceIn(0, width))) }
        val stops = List(placeables.size) { i -> inset + (width - 2 * inset) * i / last }
        val lefts = labelPositions(stops, placeables.map { it.width }, width, gap)
        layout(width, placeables.maxOf { it.height }) {
            placeables.forEachIndexed { i, p -> p.place(lefts[i], 0) }
        }
    }
}

/**
 * The widths the labels are given within [room] in all: each its one line ([widest]) while they fit;
 * else the widest of those that can wrap is wrapped to its longest word ([narrowest]), and so on
 * until they fit. Where even every label at its longest word does not fit, each gets an equal share,
 * and a word may break.
 */
private fun labelWidths(
    widest: List<Int>,
    narrowest: List<Int>,
    room: Int,
): List<Int> {
    val widths = widest.toMutableList()
    while (widths.sum() > room) {
        val wrappable = widths.indices.filter { widths[it] > narrowest[it] }
        if (wrappable.isEmpty()) return List(widths.size) { room / widths.size }
        val most = wrappable.maxBy { widths[it] }
        widths[most] = narrowest[most]
    }
    return widths
}

/**
 * Where each label of [widths] starts: centred under its stop ([stops], in pixels), inside [width],
 * then pushed right off the label before it and back left off the one after, at least [gap] apart.
 */
private fun labelPositions(
    stops: List<Float>,
    widths: List<Int>,
    width: Int,
    gap: Int,
): List<Int> {
    val lefts = stops.indices.map { i -> (stops[i] - widths[i] / 2f).roundToInt().coerceIn(0, width - widths[i]) }
    val placed = lefts.toMutableList()
    for (i in 1 until placed.size) placed[i] = maxOf(placed[i], placed[i - 1] + widths[i - 1] + gap)
    placed[placed.lastIndex] = minOf(placed.last(), width - widths.last())
    for (i in placed.lastIndex - 1 downTo 0) placed[i] = minOf(placed[i], placed[i + 1] - gap - widths[i])
    return placed.map { it.coerceAtLeast(0) }
}

/**
 * *Wait when busy* (#1120) as the settings' search finds it: its name, description and the stops'
 * labels.
 */
@Composable
internal fun musicBrainzWaitIndexRow(): IndexedRow {
    val title = stringResource(R.string.settings_musicbrainz_wait)
    val summary = stringResource(R.string.settings_musicbrainz_wait_summary)
    return IndexedRow(title, listOf(title, summary) + Settings.MUSICBRAINZ_WAITS.map { waitLabel(it) })
}
