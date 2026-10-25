package app.sleevesight.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.idleUntil
import app.sleevesight.core.settings.LinkStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Link style* (#519) through the app's own path: the stored setting, `AppGraph.linkStyle`, and
 * the theme `MainActivity` sets up, to a link on a real screen (the first-run screen's privacy
 * policy link).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkStyleAppTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun link(): Bitmap =
        compose
            .onNodeWithText(PRIVACY_POLICY_SHOWN)
            .performScrollTo()
            .captureToImage()
            .asAndroidBitmap()

    private fun Bitmap.counts(): Map<Int, Int> =
        IntArray(width * height)
            .also { getPixels(it, 0, width, 0, 0, width, height) }
            .toList()
            .groupingBy { it }
            .eachCount()

    @Test
    fun theStoredStyleReachesTheAppsLinks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            val underlined = link().counts()
            val paper = underlined.maxBy { it.value }.key

            runBlocking { app.graph.settings.update { it.copy(developerMode = true, linkStyle = LinkStyle.HIGHLIGHT) } }
            idleUntil("the app's link style read") { app.graph.linkStyle.value == LinkStyle.HIGHLIGHT }
            compose.waitForIdle()
            // The highlight is one colour, over most of the text's box, that the underlined link
            // has only at its glyphs' smoothed edges.
            val (fill, filled) = link().counts().filterKeys { it != paper }.maxBy { it.value }
            val before = underlined[fill] ?: 0
            assertTrue(
                "filled: $filled pixels of #${Integer.toHexString(fill)}, $before before",
                filled > FILLED && before < EDGES,
            )
        }
    }

    private companion object {
        const val FILLED = 1000
        const val EDGES = 250
    }
}
