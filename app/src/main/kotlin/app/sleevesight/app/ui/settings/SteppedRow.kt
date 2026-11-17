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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration

// The stepped slider of a setting chosen from a range (#1120, #1164, #1126): *Wait when busy*,
// *Retry when the AI fails*, and *History*'s lifetimes.

private const val DIMMED_ALPHA = 0.38f

/** The slider's thumb: a circle this wide, whose half the track is inset by at each end. */
private val THUMB = 24.dp

private val TRACK = 4.dp
private val STOP = 10.dp

/** The least room between two labels under the stops. */
private val LABEL_GAP = 8.dp

/** The test tags of a [SteppedRow]: its slider, the labels under its stops, and its value. */
internal data class SteppedTags(
    val slider: String,
    val stops: String,
    val value: String,
)

/**
 * A [SteppedRow]'s stops, in order: each one's value ([values]), its short [labels] (under its stop
 * and at the row's right: *15 s*, *1 h*, *Forever*), and its words for a screen reader ([spoken]:
 * *15 seconds*, *1 hour*).
 */
internal class Stops<T>(
    val values: List<T>,
    val labels: List<String>,
    val spoken: List<String>,
)

/**
 * How a [SteppedRow] looks beyond its stops: where it [start]s (at the name of the badged row above
 * it, [UNDER_BADGE], for a row nested under a source), what goes before its title ([leading], a
 * source's badge, [ROW_MARK] a side, #1126), and whether every stop is labelled or only the first
 * and the last ([endsOnly], for a row among several alike, #1126).
 */
internal data class SteppedLook(
    val start: Dp = UNDER_BADGE,
    val leading: (@Composable () -> Unit)? = null,
    val endsOnly: Boolean = false,
)

/**
 * Where a [SteppedRow] stores a move only once the thumb is let go, and asks first before a stop
 * lower than the stored one (#1126: a shorter *Keep history for* deletes at once): [ask] shows the
 * question for the stop let go at, by its words ([Stops.spoken]), and calls `confirm` to store it or
 * `cancel` to put the thumb back. A higher stop is stored at once.
 */
internal fun interface AskBeforeLower {
    @Composable
    fun Ask(
        spoken: String,
        confirm: () -> Unit,
        cancel: () -> Unit,
    )
}

/**
 * A setting chosen from [stops] on a stepped slider (#1120, #1164, #1126): the row's [title], with
 * the value chosen at its right, beside it; its description ([summary], given the stop shown, which
 * follows a drag before the store does, and its label, so it can name it; none where null); then
 * the slider, one stop for each of [stops], [stored] the index of the one stored, each with its
 * label under it ([StopLabels]), or only the ends' ([SteppedLook.endsOnly]). A move is stored at
 * once ([choose]), or, with [askBeforeLower], once the thumb is let go, a lower stop asked first. A
 * screen reader reads the slider by the row's name, with the value in words. Where the setting does
 * not apply ([applies] false), the whole row dims and cannot be moved (#999). The slider has a thumb
 * and a track of its own ([Thumb], [StopsTrack]), as the picked design draws them, in the colour of
 * the settings' other controls (the switches', #1126).
 */
@Suppress("LongParameterList") // What the row shows, and where a move goes.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SteppedRow(
    title: String,
    summary: (@Composable (T, String) -> String)?,
    stops: Stops<T>,
    stored: Int,
    applies: Boolean,
    tags: SteppedTags,
    look: SteppedLook = SteppedLook(),
    askBeforeLower: AskBeforeLower? = null,
    choose: (T) -> Unit,
) {
    val alpha = if (applies) 1f else DIMMED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val indices = stops.values.indices
    // Follows a drag at once; the stored value catches up as it is written.
    var shown by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    // The stop let go at that waits for an answer ([askBeforeLower]).
    var asking by remember { mutableStateOf<Int?>(null) }
    val index = shown.roundToInt().coerceIn(indices)
    val labels = stops.labels
    Column(Modifier.fillMaxWidth().padding(start = look.start, top = 8.dp, bottom = 8.dp)) {
        SteppedTitle(title, labels[index], look.leading, alpha, tags.value)
        summary?.let { Description(title, it(stops.values[index], labels[index]), color = color) }
        StopsSlider(
            value = shown,
            onValueChange = { moved ->
                // Against the stop shown, not the one stored: a quick drag back to where it started
                // is written too, before the store has answered the move away.
                val before = shown.roundToInt().coerceIn(indices)
                shown = moved
                val chosen = moved.roundToInt().coerceIn(indices)
                if (askBeforeLower == null && chosen != before) choose(stops.values[chosen])
            },
            onRelease =
                askBeforeLower?.let {
                    {
                        val chosen = shown.roundToInt().coerceIn(indices)
                        when {
                            chosen > stored -> choose(stops.values[chosen])
                            chosen < stored -> asking = chosen
                            else -> shown = stored.toFloat()
                        }
                    }
                },
            spoken = title to stops.spoken[index],
            count = stops.values.size,
            alpha = alpha,
            tag = tags.slider,
        )
        StopLabels(if (look.endsOnly) endsOnly(labels) else labels, color, tags.stops)
    }
    asking?.let { lower ->
        askBeforeLower?.Ask(
            spoken = stops.spoken[lower],
            confirm = {
                asking = null
                choose(stops.values[lower])
            },
            cancel = {
                asking = null
                shown = stored.toFloat()
            },
        )
    }
}

/**
 * A [SteppedRow]'s slider: [count] stops, at [value], [onValueChange] following a move and
 * [onRelease], when given, called when the thumb is let go. A screen reader reads it by
 * [spoken]'s name, with its value in words. Dimmed by [alpha] as one layer, and not to be moved
 * while dimmed.
 */
@Suppress("LongParameterList") // Where it is, what a move does, and how it is read and drawn.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onRelease: (() -> Unit)?,
    spoken: Pair<String, String>,
    count: Int,
    alpha: Float,
    tag: String,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier
                .fillMaxWidth()
                // Dimmed as one layer, so the track does not show through the thumb.
                .alpha(alpha)
                .testTag(tag)
                .semantics {
                    contentDescription = spoken.first
                    stateDescription = spoken.second
                },
        enabled = alpha == 1f,
        onValueChangeFinished = onRelease,
        valueRange = 0f..(count - 1).toFloat(),
        steps = count - 2,
        thumb = { Thumb() },
        track = { state -> StopsTrack(state, count) },
    )
}

/** [labels] with those between the first and the last left blank: a slider labelled at its ends (#1126). */
private fun endsOnly(labels: List<String>): List<String> =
    labels.mapIndexed { i, label -> if (i == 0 || i == labels.lastIndex) label else "" }

/**
 * A [SteppedRow]'s first line: [leading], when given, then its [title], and at its right the
 * [value] chosen, tagged [valueTag], read with the slider rather than on its own; dimmed by [alpha].
 */
@Composable
private fun SteppedTitle(
    title: String,
    value: String,
    leading: (@Composable () -> Unit)?,
    alpha: Float,
    valueTag: String,
) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.let {
            Box(Modifier.alpha(alpha)) { it() }
            Spacer(Modifier.width(ROW_GAP))
        }
        Text(
            searchMarked(title),
            modifier = Modifier.weight(1f).testTag(ROW_TITLE_TAG),
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
        Spacer(Modifier.width(16.dp))
        // Read with the slider, not on its own.
        Text(
            value,
            modifier = Modifier.testTag(valueTag).semantics { hideFromAccessibility() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

/** The gap between a [SteppedRow]'s [leading][SteppedLook.leading] and its title, as a badged row's. */
private val ROW_GAP = 16.dp

/** The index of the stop of [stops] nearest [value]: a value stored that is not a stop shows at the nearest. */
internal fun nearestStop(
    stops: List<Duration>,
    value: Duration,
): Int = stops.indices.minBy { abs((stops[it] - value).inWholeMilliseconds) }

/** The slider's thumb, a circle in the colour of the settings' other controls (the switches', #1126). */
@Composable
private fun Thumb() {
    Box(
        Modifier
            .size(THUMB)
            .background(MaterialTheme.colorScheme.onSurface, CircleShape),
    )
}

/**
 * The slider's track: a line, in the colour of the settings' other controls up to the thumb (the
 * switches', #1126), with a dot at each of its [stops], filled up to the thumb. Laid out by the
 * slider between the thumb's middles at either end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopsTrack(
    state: SliderState,
    stops: Int,
) {
    val active = MaterialTheme.colorScheme.onSurface
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
    tag: String,
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
        modifier = Modifier.fillMaxWidth().testTag(tag),
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
