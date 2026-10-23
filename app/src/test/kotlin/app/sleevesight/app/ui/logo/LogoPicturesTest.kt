package app.sleevesight.app.ui.logo

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The header logo and the intros as drawn (#383), with native graphics on a phone's width and
 * density. Each picture is checked for its colours where they belong, and kept as a PNG when
 * `SLEEVESIGHT_PICTURES` names a directory, for looking at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun keep(
        name: String,
        bitmap: Bitmap,
    ) {
        val dir = out ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun Bitmap.pixels(): List<Int> {
        val all = IntArray(width * height)
        getPixels(all, 0, width, 0, 0, width, height)
        return all.toList()
    }

    private fun near(
        a: Int,
        b: Color,
    ): Boolean {
        val c = b.toArgb()
        return listOf(16, 8, 0).all { shift -> abs((a shr shift and 0xFF) - (c shr shift and 0xFF)) <= 6 }
    }

    /** [content] as an activity's content, in the app's theme, light or [dark]. */
    private fun show(
        dark: Boolean,
        content: @androidx.compose.runtime.Composable () -> Unit,
        check: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { it.setContent { SleevesightTheme(darkTheme = dark) { content() } } }
        compose.waitForIdle()
        check()
    }

    @Test
    fun theHeaderLogoIsSleeveInInkAndSightInRedWithTheRecord() {
        for (dark in listOf(false, true)) {
            val colors = if (dark) LogoColors.Dark else LogoColors.Light
            show(dark, { HeaderLogo(Modifier.testTag("logo"), height = HEADER_LOGO_HEIGHT * 3) }) {
                val logo = compose.onNodeWithTag("logo").captureToImage().asAndroidBitmap()
                keep("header-logo-${if (dark) "dark" else "light"}", logo)
                // "Sleeve" in the left 40%, "sight" after it, the record last: from the box's
                // proportions in units (Sleeve to 44.99, sight from 46.94 to 109.36, of 192.11).
                val third = { from: Float, to: Float ->
                    val left = (logo.width * from).toInt()
                    Bitmap.createBitmap(logo, left, 0, (logo.width * (to - from)).toInt(), logo.height)
                }
                assertTrue("ink in Sleeve", third(0f, .4f).pixels().any { near(it, colors.ink) })
                assertTrue("no red in Sleeve", third(0f, .4f).pixels().none { near(it, colors.sight) })
                assertTrue("red in sight", third(.45f, .7f).pixels().any { near(it, colors.sight) })
                assertTrue("the record's rim", third(.8f, 1f).pixels().any { near(it, colors.rim) })
            }
        }
    }

    @Test
    fun theHomeHeaderShowsTheLogo() {
        for (dark in listOf(false, true)) {
            show(dark, {
                HomeScreen(accessGranted = true, onOpenFirstRun = {}, onOpenSettings = {}) { Text("Now playing") }
            }) {
                val theme = if (dark) "dark" else "light"
                keep("home-header-$theme", compose.onRoot().captureToImage().asAndroidBitmap())
            }
        }
    }

    @Test
    fun eachIntroDrawsItsMarkAndLandsOnTheHeader() {
        val moments =
            mapOf(
                IntroVariant.C to listOf(400L, 1500L, 2900L, 3600L, 4100L),
                IntroVariant.D to listOf(400L, 1900L, 3600L, 4400L),
                IntroVariant.F to listOf(1400L, 1900L, 2900L, 3600L),
                IntroVariant.R1 to listOf(1200L, 2000L, 3200L, 3700L),
                IntroVariant.R2 to listOf(300L, 1500L, 3000L, 3500L, 4400L),
            )
        for (dark in listOf(false, true)) {
            moments.forEach { (variant, times) -> times.forEach { picture(variant, it, dark) } }
        }
    }

    /** [variant] at [t] ms over the home screen, its logo landing on the header's. */
    private fun picture(
        variant: IntroVariant,
        t: Long,
        dark: Boolean,
    ) {
        val intro = LogoIntro(variant).apply { elapsed = t.toFloat() }
        show(dark, {
            val background = MaterialTheme.colorScheme.surface
            val colors = logoColors()
            CompositionLocalProvider(LocalLogoIntro provides intro) {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(accessGranted = true, onOpenFirstRun = {}, onOpenSettings = {}) { Text("Now playing") }
                    Canvas(Modifier.fillMaxSize()) { drawIntro(variant, t.toFloat(), intro.target, background, colors) }
                }
            }
        }) {
            val picture = compose.onRoot().captureToImage().asAndroidBitmap()
            val theme = if (dark) "dark" else "light"
            keep("intro-${variant.name.lowercase()}-${"%04d".format(t)}-$theme", picture)
            val colors = if (dark) LogoColors.Dark else LogoColors.Light
            val drawn = listOf(colors.ink, colors.sight, colors.rim)
            // Once faded in, the logo's own colours, unblended.
            if (t >= FADED_IN) {
                assertTrue("$variant at $t: the logo drawn", picture.pixels().any { p -> drawn.any { near(p, it) } })
            }
        }
    }

    private companion object {
        const val FADED_IN = 700L
    }
}
