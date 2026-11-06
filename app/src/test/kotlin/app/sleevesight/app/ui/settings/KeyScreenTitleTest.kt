package app.sleevesight.app.ui.settings

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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.typographyOf
import app.sleevesight.core.settings.FontChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A *Load keys* step's number sits in the middle of its circle (#1012), for each step, in either
 * *Font*, at the default font size and a large one: drawn in colours apart (circle red, number
 * blue, title black), the number's ink is centred in the circle's to within a pixel, across where
 * its weight balances (a 1's stem, not the middle of stem and flag), down by its outline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyScreenTitleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun numberCentredInCircle() = assertCentred(fontScale = 1f)

    @Test
    fun numberCentredInCircleAtLargeFont() = assertCentred(fontScale = 2f)

    private fun assertCentred(fontScale: Float) {
        for (font in FontChoice.entries) {
            for (step in 1..STEPS) assertCentred(fontScale, font, step)
        }
    }

    private fun assertCentred(
        fontScale: Float,
        font: FontChoice,
        step: Int,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        MaterialTheme(colorScheme = COLOURS, typography = typographyOf(font)) {
                            Box(Modifier.background(Color.White).testTag(TAG)) {
                                KeyScreenTitle(R.string.load_keys_keys_title, step = step)
                            }
                        }
                    }
                }
            }
            val image = compose.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
            val circle = ink(image) { r, g, b -> r > 200 && g < 120 && b < 120 }
            val number = ink(image, circle) { r, g, b -> b > 150 && r < 120 && g < 120 }
            val case = "step $step, $font, font scale $fontScale"
            val across = balanceAcross(image, number, circle)
            assertEquals("$case: horizontal centre", centre(circle[0], circle[2]), across, 1.0)
            assertEquals("$case: vertical centre", centre(circle[1], circle[3]), centre(number[1], number[3]), 1.0)
        }
    }

    /** The bounds (left, top, right, bottom) of the pixels [matching], within [area] if given. */
    private fun ink(
        image: Bitmap,
        area: IntArray? = null,
        matching: (Int, Int, Int) -> Boolean,
    ): IntArray {
        val within = area ?: intArrayOf(0, 0, image.width - 1, image.height - 1)
        var bounds = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        for (y in within[1]..within[3]) {
            for (x in within[0]..within[2]) {
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

    /**
     * Where the number's blue ink around [number] balances across, as a pixel index as [centre]
     * gives: each pixel's column weighed by how blue it is over the white (its blue less its red,
     * none for the red circle or the black title). [number] is widened by a pixel each way, within
     * [circle], so the faint anti-aliased edges count too.
     */
    private fun balanceAcross(
        image: Bitmap,
        number: IntArray,
        circle: IntArray,
    ): Double {
        var weight = 0.0
        var moment = 0.0
        for (y in maxOf(number[1] - 1, circle[1])..minOf(number[3] + 1, circle[3])) {
            for (x in maxOf(number[0] - 1, circle[0])..minOf(number[2] + 1, circle[2])) {
                val p = image.getPixel(x, y)
                val blue = ((p and 0xFF) - (p shr 16 and 0xFF)).coerceAtLeast(0)
                weight += blue
                moment += blue * x
            }
        }
        return moment / weight
    }

    private fun centre(
        from: Int,
        to: Int,
    ) = (from + to) / 2.0

    private companion object {
        const val TAG = "title"

        /** *Load keys*' steps. */
        const val STEPS = 4
        val COLOURS =
            lightColorScheme(
                surface = Color.White,
                onSurface = Color.Black,
                outlineVariant = Color.Red,
                onSurfaceVariant = Color.Blue,
            )
    }
}
