package app.sleevesight.app.ui.logo

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The turning record beside an in-progress message (#785), and the row of the two.

/**
 * The phone's animator duration scale as [SpinningRecord] reads it (#785): 0 under *Remove
 * animations*, where the record stands still. The app provides it once, at its root
 * ([followedAnimatorDurationScale], where `MainActivity` sets its content), so one observer follows
 * the setting for every record shown; a test may provide a value of its own. Null, the default, where
 * nothing provides it (a preview, a test without the root): each record then reads the phone's
 * setting itself and follows it while it shows.
 */
val LocalAnimatorDurationScale = compositionLocalOf<Float?> { null }

/**
 * The record that turns beside an in-progress message (#785): the logo's record simplified
 * ([drawSpinnerRecord]), in the logo's colours for the theme ([logoColors]), 20 sp across as the
 * font size sets it, so a larger font gives a larger record as it gives larger text. It turns
 * clockwise at 33⅓ rpm, one turn in 1.8 s, for as long as it shows; under *Remove animations*
 * ([LocalAnimatorDurationScale] 0) it stands still, upright. A screen reader skips it: it has no
 * semantics but its test tag, [SPINNING_RECORD_TAG], and the message beside it says what is under way.
 */
@Composable
fun SpinningRecord(modifier: Modifier = Modifier) {
    val side = with(LocalDensity.current) { RECORD_SIZE.toDp() }
    val colors = logoColors()
    val degrees = turn()
    Canvas(modifier.size(side).clearAndSetSemantics { testTag = SPINNING_RECORD_TAG }) {
        drawSpinnerRecord(colors, degrees.value)
    }
}

/**
 * How far [SpinningRecord] has turned, in degrees, read as it draws: clockwise, one turn in
 * [TURN_MS], for ever; 0 under *Remove animations*, the record upright.
 */
@Composable
private fun turn(): State<Float> =
    if (animatorDurationScale() == 0f) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "record").animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(TURN_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "record turn",
        )
    }

/**
 * An in-progress message (#785): the turning record ([SpinningRecord]), [RECORD_GAP], then [text]
 * in [style] and [color], which takes the rest of the width and wraps there. By a message that wraps,
 * the record sits by its first line, its centre on the line's, as picked on the issue (#785).
 * [modifier] goes on the row (its padding); [textModifier] on the message (its test tag, its live
 * region, its heading), which a screen reader reads as it is, the record not at all.
 *
 * The row is as tall as the message: a record taller than the first line (20 sp beside a
 * `bodySmall` line of 16 sp) overhangs the row by as much above as below, drawn all the same, so a
 * row keeps its height when its record goes and what is under it does not move.
 *
 * Without [record], the message alone, at the row's start. It stays in the same place in the
 * composition either way, so its node, and a live region on it, last from one state to the next.
 * A message whose state ends draws `Busy(…, record = false)` rather than a `Text` of its own: a new
 * node in place of the old would most likely go unannounced.
 */
@Suppress("LongParameterList") // The message, its style, colour and modifiers, and whether the record shows.
@Composable
fun Busy(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textModifier: Modifier = Modifier,
    record: Boolean = true,
) {
    // The message's first line's centre, from its last layout, which runs as it is measured.
    val firstLine = remember { FirstLine() }
    Layout(
        content = {
            // The record when it shows; the message after it keeps its slot either way.
            if (record) SpinningRecord()
            Text(
                text,
                modifier = textModifier,
                color = color,
                style = style,
                onTextLayout = { firstLine.centre = (it.getLineTop(0) + it.getLineBottom(0)) / 2 },
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        // The record, when it shows, the first child; the message, always the last.
        val disc = if (measurables.size > 1) measurables.first().measure(Constraints()) else null
        val side = disc?.let { it.width + RECORD_GAP.roundToPx() } ?: 0
        val room =
            if (constraints.hasBoundedWidth) (constraints.maxWidth - side).coerceAtLeast(0) else Constraints.Infinity
        val message = measurables.last().measure(Constraints(maxWidth = room))
        val width = (side + message.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = message.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // The record's centre on the first line's: above the row's top and below its bottom
            // by as much, by a line less tall than it.
            disc?.let {
                val anchor = firstLine.centre ?: (message.height / 2f)
                it.placeRelative(0, (anchor - it.height / 2f).roundToInt())
            }
            message.placeRelative(side, 0)
        }
    }
}

/** The test tag of [SpinningRecord], in the unmerged tree. */
const val SPINNING_RECORD_TAG: String = "spinning-record"

/**
 * The phone's animator duration scale ([LocalAnimatorDurationScale]): as provided (the root's, or a
 * test's), else read and followed here, for want of a provider.
 */
@Composable
internal fun animatorDurationScale(): Float = LocalAnimatorDurationScale.current ?: followedAnimatorDurationScale()

/**
 * The phone's animator duration scale (`Settings.Global.ANIMATOR_DURATION_SCALE`, which `MainActivity`
 * also reads for the intro; *Remove animations* sets it to 0), read once and followed with one
 * `ContentObserver`, registered while this is composed and unregistered when it leaves (#785). The
 * app's root provides it as [LocalAnimatorDurationScale]; a record reads it itself only for want of
 * a provider. A change reaches only what reads it: the records shown.
 */
@Composable
fun followedAnimatorDurationScale(): Float {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(readScale(resolver)) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scale = readScale(resolver)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

private fun readScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Where [Busy]'s message's first line is centred, as its layout last said, in pixels. */
private class FirstLine {
    var centre: Float? = null
}

// The record's size, as the font scales it; one turn, 33⅓ rpm; the space between it and its message.
private val RECORD_SIZE = 20.sp
private const val FULL_TURN = 360f
private const val TURN_MS = 1800
private val RECORD_GAP = 8.dp
