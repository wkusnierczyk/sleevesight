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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
 * animations*, where the record stands still. Null, the default, reads the phone's setting
 * (`Settings.Global.ANIMATOR_DURATION_SCALE`, which `MainActivity` reads for the intro), and follows
 * it while the record shows; a test provides a value of its own.
 */
val LocalAnimatorDurationScale = staticCompositionLocalOf<Float?> { null }

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
 */
@Composable
fun Busy(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textModifier: Modifier = Modifier,
) {
    // The message's first line's centre, from its last layout, which runs as it is measured.
    val firstLine = remember { FirstLine() }
    Layout(
        content = {
            SpinningRecord()
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
        val record = measurables[0].measure(Constraints())
        val side = record.width + RECORD_GAP.roundToPx()
        val room =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - side).coerceAtLeast(
                    0,
                )
            } else {
                Constraints.Infinity
            }
        val message = measurables[1].measure(Constraints(maxWidth = room))
        val anchor = firstLine.centre ?: (message.height / 2f)
        // The record's top on the anchor; above the row's top (a line less tall than the record),
        // the message goes down by as much instead.
        val top = (anchor - record.height / 2f).roundToInt()
        val down = (-top).coerceAtLeast(0)
        val width = (side + message.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height =
            maxOf(
                down + message.height,
                top + down + record.height,
            ).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            record.placeRelative(0, top + down)
            message.placeRelative(side, down)
        }
    }
}

/** The test tag of [SpinningRecord], in the unmerged tree. */
const val SPINNING_RECORD_TAG: String = "spinning-record"

/** The phone's animator duration scale ([LocalAnimatorDurationScale]): a test's, or the phone's. */
@Composable
internal fun animatorDurationScale(): Float = LocalAnimatorDurationScale.current ?: systemAnimatorDurationScale()

/** The phone's animator duration scale, followed while it is read: *Remove animations* sets it to 0. */
@Composable
private fun systemAnimatorDurationScale(): Float {
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
