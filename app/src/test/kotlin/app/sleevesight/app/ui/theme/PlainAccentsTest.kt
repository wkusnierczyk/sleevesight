package app.sleevesight.app.ui.theme

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.home.artScheme
import app.sleevesight.app.ui.settings.FoldedSection
import app.sleevesight.app.ui.settings.SectionFolds
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SwitchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Headings and switches without the accent colour (#442): headings bold in the text colour,
 * switches in the text colour when on and grey when off, and the WCAG AA contrast of the links and
 * the switches. Drawn with native graphics on a phone's width and density.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlainAccentsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val pictures = AccentPictures(compose)

    @Test
    fun aSectionsChipIsInTheTextColourAndItsPanelsHeadingBoldInIt() {
        for (dark in listOf(false, true)) {
            for (open in listOf(false, true)) {
                val folds = SectionFolds(if (open) setOf(SettingsSection.ABOUT) else emptySet())
                pictures.show(dark, { Column { FoldedSection(SettingsSection.ABOUT, folds) {} } }) { scheme ->
                    val style = pictures.styleOf(app.getString(R.string.settings_section_about))
                    // The panel's heading bold (#442); the chip's name as a genre chip's (#649).
                    if (open) assertEquals(FontWeight.Bold, style.fontWeight)
                    assertEquals(scheme.onSurface, style.color)
                }
            }
        }
    }

    @Test
    fun theAppFontHasABoldFaceOfItsOwnSoHeadingsAreNotSynthesized() {
        val weights = (Roboto as FontListFontFamily).fonts.map { it.weight }
        assertTrue("$weights", FontWeight.Bold in weights)
    }

    /**
     * The switch at the end of a row drawn as [picture], sampled where Material draws its parts: a
     * track of 52 by 32 dp with a 2 dp outline, a thumb of 24 dp centred 16 dp from the track's end
     * when on, and of 16 dp centred 16 dp from its start when off.
     */
    private class SwitchPicture(
        private val picture: Bitmap,
        density: Float,
    ) {
        private val dp = { value: Int -> (value * density).toInt() }
        private val end = picture.width - 1
        private val middle = picture.height / 2

        /** The track's start, left of where the thumb is when off. */
        val start get() = picture.getPixel(end - dp(TRACK_WIDTH - 5), middle)

        /** Where the thumb is when on. */
        val onThumb get() = picture.getPixel(end - dp(16), middle)

        /** Where the thumb is when off. */
        val offThumb get() = picture.getPixel(end - dp(TRACK_WIDTH - 16), middle)

        /** The track's outline, at the middle of its top. */
        val outline get() = picture.getPixel(end - dp(TRACK_WIDTH / 2), middle - dp(TRACK_HEIGHT / 2) + dp(1))
    }

    /** On, as #442 has it: the track and its outline in the text colour, the thumb in the surface's. */
    private fun SwitchPicture.looksOn(scheme: ColorScheme) =
        start.near(scheme.onSurface) && outline.near(scheme.onSurface) && onThumb.near(scheme.surface)

    /** Off: a grey track inside a grey outline, and a grey thumb at its start. */
    private fun SwitchPicture.looksOff(scheme: ColorScheme) =
        start.near(scheme.surfaceContainerHighest) &&
            onThumb.near(scheme.surfaceContainerHighest) &&
            offThumb.near(scheme.outline) &&
            outline.near(scheme.outline)

    @Test
    fun aSwitchIsTheTextColourWhenOnAndGreyWhenOff() {
        val density = app.resources.displayMetrics.density
        for (dark in listOf(false, true)) {
            for (checked in listOf(true, false)) {
                pictures.show(dark, {
                    Column(Modifier.testTag(TAG)) { SwitchRow("Switch", null, checked = checked) {} }
                }) { scheme ->
                    val picture = pictures.picture(TAG)
                    val switch = SwitchPicture(picture, density)
                    val case = "dark=$dark, checked=$checked"
                    assertTrue("$case: no primary colour", picture.all().none { it.near(scheme.primary) })
                    // Each switch has its own look and not the other's.
                    assertEquals("$case: looks on", checked, switch.looksOn(scheme))
                    assertEquals("$case: looks off", !checked, switch.looksOff(scheme))
                }
            }
        }
    }

    @Test
    fun linksAndSwitchesMeetWcagAaContrastInTheAppsAndTheArtsSchemes() {
        val art = { color: Int -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) } }
        val schemes =
            listOf(lightColorScheme(), darkColorScheme()) +
                ART_SEEDS.flatMap { seed ->
                    listOf(false, true).map { dark -> checkNotNull(artScheme(art(seed), dark)) }
                }
        // By index, as composition may run more than once.
        val switches = arrayOfNulls<Pair<ColorScheme, SwitchColors>>(schemes.size + 2)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    // The app's dynamic scheme, as the phone gives it, too.
                    for ((i, dark) in listOf(false, true).withIndex()) {
                        SleevesightTheme(darkTheme = dark) {
                            switches[i] = MaterialTheme.colorScheme to plainSwitchColors()
                        }
                    }
                    for ((i, scheme) in schemes.withIndex()) {
                        MaterialTheme(colorScheme = scheme) { switches[i + 2] = scheme to plainSwitchColors() }
                    }
                }
            }
            compose.waitForIdle()
        }
        for ((scheme, colors) in switches.map(::checkNotNull)) {
            val surface = scheme.surface
            assertAtLeast(TEXT, scheme.onSurface, surface, "link text")
            assertAtLeast(TEXT, scheme.onSurfaceVariant, surface, "small link text")
            assertAtLeast(NON_TEXT, colors.checkedTrackColor, surface, "on: the track against the surface")
            assertAtLeast(NON_TEXT, colors.checkedThumbColor, colors.checkedTrackColor, "on: the thumb")
            assertAtLeast(NON_TEXT, colors.uncheckedBorderColor, surface, "off: the track's outline")
            assertAtLeast(NON_TEXT, colors.uncheckedThumbColor, colors.uncheckedTrackColor, "off: the thumb")
        }
    }

    private fun assertAtLeast(
        ratio: Double,
        a: Color,
        b: Color,
        what: String,
    ) {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        val contrast = (light + 0.05) / (dark + 0.05)
        assertTrue("$what: $contrast, under $ratio", contrast >= ratio)
    }

    private companion object {
        const val TAG = "row"
        const val TRACK_WIDTH = 52
        const val TRACK_HEIGHT = 32

        // WCAG 2.1 AA: text (1.4.3) and a component's states (1.4.11).
        const val TEXT = 4.5
        const val NON_TEXT = 3.0

        // Album art of one colour each: red, orange, yellow, green, blue, purple, pink, brown, grey,
        // black and white (the last three with next to no chroma, which `Score` falls back on).
        val ART_SEEDS =
            listOf(
                0xFFD32F2F,
                0xFFC0661E,
                0xFFF2D024,
                0xFF2E7D32,
                0xFF1E3A8A,
                0xFF6A1B9A,
                0xFFF48FB1,
                0xFF5D4037,
                0xFF808080,
                0xFF000000,
                0xFFFFFFFF,
            ).map { it.toInt() }
    }
}
