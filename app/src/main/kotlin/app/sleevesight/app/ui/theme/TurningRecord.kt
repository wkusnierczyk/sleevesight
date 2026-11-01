package app.sleevesight.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.sleevesight.app.ui.logo.drawTextRecord
import app.sleevesight.app.ui.logo.logoColors

// The small turning record beside a line that says something is under way (#773; #785 and #786
// reuse it): the logo's record, simplified for text size.

/**
 * The logo's record at text size, turning while something is under way: first beside *Search with
 * AI*'s "Searching with AI…" (#773), and shared with #785 and #786, which show it beside their own
 * lines. The record simplified ([drawTextRecord]): the rim's colour as an outline only (near black
 * on light, grey on dark, as the logo's, [logoColors]), all inside it the red label, with its white
 * face scaled up with it. [size] across, 20 sp by default, so it grows with the font as the text
 * beside it does. One turn per 1.8 s (33⅓ rpm), linear, for ever; still under the system's *Remove
 * animations* ([animationsRemoved], the animator duration scale at 0), as the logo intro is. Hidden
 * from a screen reader: the text beside it says what is under way.
 */
@Composable
internal fun TurningRecord(
    modifier: Modifier = Modifier,
    size: TextUnit = RECORD_SIZE,
) {
    val context = LocalContext.current
    val turning = remember(context) { !animationsRemoved(context) }
    val colors = logoColors()
    // Read as it is drawn, so a turn redraws the record alone.
    val turn =
        if (turning) {
            rememberInfiniteTransition(label = "record").animateFloat(
                initialValue = 0f,
                targetValue = FULL_TURN,
                animationSpec = infiniteRepeatable(tween(TURN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
                label = "turn",
            )
        } else {
            null
        }
    val across = with(LocalDensity.current) { size.toDp() }
    Canvas(
        modifier
            .size(across)
            .clearAndSetSemantics { this.turning = turning },
    ) {
        drawTextRecord(colors, center, this.size.minDimension / 2, turn?.value ?: 0f)
    }
}

/** Whether the phone's animations are removed (*Remove animations*: the animator duration scale at 0). */
internal fun animationsRemoved(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * Whether a [TurningRecord] turns, for tests: a property a screen reader does not read, so the
 * record stays hidden from it.
 */
internal val RecordTurning = SemanticsPropertyKey<Boolean>("RecordTurning")
private var SemanticsPropertyReceiver.turning by RecordTurning

/** The record's size beside a line of body text (#773). */
internal val RECORD_SIZE: TextUnit = 20.sp

private const val FULL_TURN = 360f

// One turn at 33⅓ rpm.
private const val TURN_MILLIS = 1800
