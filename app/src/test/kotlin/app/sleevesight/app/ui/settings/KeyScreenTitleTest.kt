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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A *Load keys* step's number sits in the middle of its circle (#1012), at the default font size
 * and a large one: drawn in colours apart (circle red, number blue, title black), the number's ink
 * is centred in the circle's to within a pixel.
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        MaterialTheme(colorScheme = COLOURS) {
                            Box(Modifier.background(Color.White).testTag(TAG)) {
                                KeyScreenTitle(R.string.load_keys_keys_title, step = 3)
                            }
                        }
                    }
                }
            }
            val image = compose.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
            val circle = ink(image) { r, g, b -> r > 200 && g < 120 && b < 120 }
            val number = ink(image, circle) { r, g, b -> b > 150 && r < 120 && g < 120 }
            assertEquals("horizontal centre", centre(circle[0], circle[2]), centre(number[0], number[2]), 1.0)
            assertEquals("vertical centre", centre(circle[1], circle[3]), centre(number[1], number[3]), 1.0)
        }
    }

    /** The bounds (left, top, right, bottom) of the pixels [matching], within [area] if given. */
    private fun ink(
        image: Bitmap,
        area: IntArray? = null,
        matching: (Int, Int, Int) -> Boolean,
    ): IntArray {
        val (x0, y0, x1, y1) = area ?: intArrayOf(0, 0, image.width - 1, image.height - 1)
        var bounds = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        for (y in y0..y1) {
            for (x in x0..x1) {
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

    private fun centre(
        from: Int,
        to: Int,
    ) = (from + to) / 2.0

    private companion object {
        const val TAG = "title"
        val COLOURS =
            lightColorScheme(
                surface = Color.White,
                onSurface = Color.Black,
                outlineVariant = Color.Red,
                onSurfaceVariant = Color.Blue,
            )
    }
}
