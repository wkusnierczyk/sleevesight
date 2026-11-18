package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

// How far, summed over red, green and blue, a pixel may be from a colour and still count as it,
// allowing for the edges' smoothing.
private const val NEAR = 60

/** At least this many of a button's pixels in a colour: its label is drawn in it. */
internal const val MIN_INK = 20

/** How many of [bitmap]'s pixels are within [NEAR] of [colour] (the pill tests, #1404). */
internal fun pixelsNear(
    bitmap: Bitmap,
    colour: Color,
): Int {
    val want = colour.toArgb()
    var count = 0
    for (x in 0 until bitmap.width) {
        for (y in 0 until bitmap.height) {
            val got = bitmap.getPixel(x, y)
            val distance =
                abs(android.graphics.Color.red(got) - android.graphics.Color.red(want)) +
                    abs(android.graphics.Color.green(got) - android.graphics.Color.green(want)) +
                    abs(android.graphics.Color.blue(got) - android.graphics.Color.blue(want))
            if (distance < NEAR) count++
        }
    }
    return count
}
