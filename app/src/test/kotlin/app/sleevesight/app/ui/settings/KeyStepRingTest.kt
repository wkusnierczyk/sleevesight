package app.sleevesight.app.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A *Load keys* step's ring shows how far along the steps are (#1022): four quarters with gaps
 * between them, the first one per step reached filled clockwise from 12 o'clock in the number's
 * quiet colour (blue here), the rest in the outline colour (red here), at the default font size and
 * a large one; a screen reader says "Step 2 of 4: QR Code"; a title with no step has no ring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyStepRingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun aQuarterFilledPerStepReached() = assertQuarters(fontScale = 1f)

    @Test
    fun aQuarterFilledPerStepReachedAtLargeFont() = assertQuarters(fontScale = 2f)

    @Test
    fun aScreenReaderSaysTheStepOfHowMany() {
        show(fontScale = 1f, step = 2, title = R.string.load_keys_scan_title) {
            val title = context().getString(R.string.load_keys_scan_title)
            compose.onNode(hasText("Step 2 of 4: $title") and isHeading()).assertIsDisplayed()
        }
    }

    @Test
    fun noStepNoRing() {
        show(fontScale = 1f, step = null) { image ->
            assertFalse("a ring with no step", hasPixel(image) { r, g, b -> (r > 200 || b > 200) && g < 120 })
        }
    }

    private fun assertQuarters(fontScale: Float) {
        for (step in 1..LOAD_KEYS_STEPS) {
            show(fontScale, step) { image ->
                val ring = ink(image) { r, g, b -> (r > 200 || b > 200) && g < 120 }
                val cx = (ring[0] + ring[2]) / 2.0
                val cy = (ring[1] + ring[3]) / 2.0
                // Along the middle of the 2 dp stroke, inside the ring's outer edge.
                val radius = (ring[2] - ring[0] + 1) / 2.0 - density()
                val case = "step $step, font scale $fontScale"
                for (quarter in 0 until LOAD_KEYS_STEPS) {
                    val middle = colourAt(image, cx, cy, radius, quarter * QUARTER + QUARTER / 2)
                    val expected = if (quarter < step) "filled" else "unfilled"
                    assertEquals("$case: quarter ${quarter + 1}", expected, middle)
                    val joint = colourAt(image, cx, cy, radius, quarter * QUARTER)
                    assertEquals("$case: the gap at ${quarter * 3 % 12} o'clock", "none", joint)
                }
            }
        }
    }

    /** What the pixel [degrees] clockwise from 12 o'clock at [radius] from the centre is drawn in. */
    private fun colourAt(
        image: Bitmap,
        cx: Double,
        cy: Double,
        radius: Double,
        degrees: Double,
    ): String {
        val angle = Math.toRadians(degrees)
        val p = image.getPixel((cx + radius * sin(angle)).roundToInt(), (cy - radius * cos(angle)).roundToInt())
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        return when {
            b > 150 && r < 120 && g < 120 -> "filled"
            r > 150 && b < 120 && g < 120 -> "unfilled"
            r > 200 && g > 200 && b > 200 -> "none"
            else -> "mixed ($r, $g, $b)"
        }
    }

    private fun show(
        fontScale: Float,
        step: Int?,
        title: Int = R.string.load_keys_keys_title,
        check: (Bitmap) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        MaterialTheme(colorScheme = COLOURS) {
                            Box(Modifier.background(Color.White).testTag(TAG)) {
                                KeyScreenTitle(title, step = step)
                            }
                        }
                    }
                }
            }
            check(compose.onNodeWithTag(TAG).captureToImage().asAndroidBitmap())
        }
    }

    private fun hasPixel(
        image: Bitmap,
        matching: (Int, Int, Int) -> Boolean,
    ): Boolean =
        (0 until image.height).any { y ->
            (0 until image.width).any { x ->
                val p = image.getPixel(x, y)
                matching(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            }
        }

    /** The bounds (left, top, right, bottom) of the pixels [matching]. */
    private fun ink(
        image: Bitmap,
        matching: (Int, Int, Int) -> Boolean,
    ): IntArray {
        var bounds = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val p = image.getPixel(x, y)
                if (matching(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)) {
                    bounds =
                        intArrayOf(minOf(bounds[0], x), minOf(bounds[1], y), maxOf(bounds[2], x), maxOf(bounds[3], y))
                }
            }
        }
        check(bounds[0] <= bounds[2]) { "no matching ink" }
        return bounds
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun density() =
        context()
            .resources.displayMetrics.density
            .toDouble()

    private companion object {
        const val TAG = "title"

        /** A quarter of the ring, in degrees. */
        const val QUARTER = 90.0
        val COLOURS =
            lightColorScheme(
                surface = Color.White,
                onSurface = Color.Black,
                outlineVariant = Color.Red,
                onSurfaceVariant = Color.Blue,
            )
    }
}
