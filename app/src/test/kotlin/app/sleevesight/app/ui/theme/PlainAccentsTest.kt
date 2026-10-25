package app.sleevesight.app.ui.theme

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.WebLink
import app.sleevesight.app.ui.home.artScheme
import app.sleevesight.app.ui.settings.SectionHeading
import app.sleevesight.app.ui.settings.SwitchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Links, headings and switches without the accent colour (#442): links in the text colour with a
 * thin line below the text, headings bold in the text colour, switches in the text colour when on
 * and grey when off, each with WCAG AA contrast. Drawn with native graphics on a phone's width and
 * density, so the pictures are the phone's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlainAccentsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    /** [content] on a surface of the app's theme, light or [dark], and the scheme it was drawn in. */
    private fun show(
        dark: Boolean,
        content: @Composable () -> Unit,
        check: (ColorScheme) -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        var scheme: ColorScheme? = null
        scenario.onActivity {
            it.setContent {
                SleevesightTheme(darkTheme = dark) {
                    scheme = MaterialTheme.colorScheme
                    Surface { content() }
                }
            }
        }
        compose.waitForIdle()
        check(checkNotNull(scheme))
    }

    private fun near(
        pixel: Int,
        color: Color,
        tolerance: Int = 8,
    ): Boolean {
        val c = color.toArgb()
        return listOf(16, 8, 0).all { shift -> abs((pixel shr shift and 0xFF) - (c shr shift and 0xFF)) <= tolerance }
    }

    private fun Bitmap.row(y: Int): IntArray = IntArray(width).also { getPixels(it, 0, width, 0, y, width, 1) }

    private fun Bitmap.all(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    /** Whether [pixel] is nearer [ink] than [paper]: part of a glyph or a line, not the background. */
    private fun inked(
        pixel: Int,
        ink: Color,
        paper: Color,
    ): Boolean {
        fun distance(c: Color) =
            listOf(16, 8, 0).sumOf { shift -> abs((pixel shr shift and 0xFF) - (c.toArgb() shr shift and 0xFF)) }
        return distance(ink) < distance(paper)
    }

    /**
     * The rows of [picture] that hold the underline: inked across at least [UNDERLINE_SHARE] of the
     * text's inked width, as no row of glyphs is.
     */
    private fun underline(
        picture: Bitmap,
        ink: Color,
        paper: Color,
    ): List<Int> {
        val columns =
            (0 until picture.width).filter { x ->
                (0 until picture.height).any { y -> inked(picture.getPixel(x, y), ink, paper) }
            }
        val span = columns.last() - columns.first() + 1
        return (0 until picture.height).filter { y ->
            picture.row(y).count { inked(it, ink, paper) } >= span * UNDERLINE_SHARE
        }
    }

    @Test
    fun aLinkIsUnderlinedInTheTextColourWithAGapBelowItsDescenders() {
        for (dark in listOf(false, true)) {
            // "privacy" has descenders (p, y) down to the font's lowest.
            show(dark, { WebLink("https://sleevesight.app/privacy.html", Modifier.testTag("link")) }) { scheme ->
                val picture = compose.onNodeWithTag("link").captureToImage().asAndroidBitmap()
                assertTrue("no primary colour", picture.all().none { near(it, scheme.primary) })
                val lines = underline(picture, scheme.onSurface, scheme.surface)
                assertTrue("dark=$dark: an underline", lines.isNotEmpty())
                assertTrue(
                    "dark=$dark: the underline in the text colour",
                    lines.any { y ->
                        picture.row(y).count { near(it, scheme.onSurface, tolerance = 40) } > picture.width / 2
                    },
                )
                // Above the underline, a row with no ink at all, then the descenders.
                val glyphs =
                    (0 until lines.first()).filter { y ->
                        y !in lines && picture.row(y).any { inked(it, scheme.onSurface, scheme.surface) }
                    }
                assertTrue("dark=$dark: glyphs above the line", glyphs.isNotEmpty())
                assertTrue(
                    "dark=$dark: a clear row between the descenders (${glyphs.last()}) and the line (${lines.first()})",
                    lines.first() - glyphs.last() > 1,
                )
            }
        }
    }

    @Test
    fun aLinkInATextIsUnderlinedAloneInTheTextColour() {
        show(dark = false, {
            // A link without styles would take Material's default, in the primary colour.
            val text =
                buildAnnotatedString {
                    append("Read it on ")
                    withLink(LinkAnnotation.Url("https://example.org", linkStyles())) { append("example.org") }
                }
            LinkedText(text, MaterialTheme.typography.bodyMedium, Modifier.testTag("text"))
        }) { scheme ->
            val picture = compose.onNodeWithTag("text").captureToImage().asAndroidBitmap()
            assertTrue("no primary colour", picture.all().none { near(it, scheme.primary) })
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithTag("text")
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layouts)
            val linkStart = layouts.single().getBoundingBox("Read it on ".length).left
            val line = picture.row(underlineRow(picture, scheme.onSurface, from = linkStart.toInt()))
            val start = line.indexOfFirst { near(it, scheme.onSurface, tolerance = 40) }
            // The line starts at the link, not under "Read it on".
            assertTrue("the line starts at $start, the link at $linkStart", abs(start - linkStart) <= 2f)
        }
    }

    /** The row of [picture] with the longest run of [ink] right of [from]: the underline. */
    private fun underlineRow(
        picture: Bitmap,
        ink: Color,
        from: Int,
    ): Int =
        (0 until picture.height).maxBy { y ->
            picture.row(y).drop(from).count { near(it, ink, tolerance = 40) }
        }

    @Test
    fun aSectionHeadingIsBoldInTheTextColour() {
        val title = app.getString(R.string.settings_section_about)
        for (dark in listOf(false, true)) {
            show(dark, { Column { SectionHeading(R.string.settings_section_about) } }) { scheme ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose
                    .onNodeWithText(title)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.GetTextLayoutResult]
                    .action
                    ?.invoke(layouts)
                val style = layouts.single().layoutInput.style
                assertEquals(FontWeight.Bold, style.fontWeight)
                assertEquals(scheme.onSurface, style.color)
            }
        }
    }

    @Test
    fun theAppFontHasABoldFaceOfItsOwnSoHeadingsAreNotSynthesized() {
        val weights = (Roboto as FontListFontFamily).fonts.map { it.weight }
        assertTrue("$weights", FontWeight.Bold in weights)
    }

    @Test
    fun aSwitchIsTheTextColourWhenOnAndGreyWhenOff() {
        for (dark in listOf(false, true)) {
            for (checked in listOf(true, false)) {
                show(dark, {
                    Column(Modifier.testTag("row")) { SwitchRow("Switch", null, checked = checked) {} }
                }) { scheme ->
                    val pixels =
                        compose
                            .onNodeWithTag("row")
                            .captureToImage()
                            .asAndroidBitmap()
                            .all()
                    val case = "dark=$dark, checked=$checked"
                    assertTrue("$case: no primary colour", pixels.none { near(it, scheme.primary) })
                    if (checked) {
                        // The track in the text colour, the thumb in the surface's, inside it.
                        assertTrue(case, pixels.count { near(it, scheme.onSurface) } > SOLID_PIXELS)
                    } else {
                        assertTrue(case, pixels.count { near(it, scheme.surfaceContainerHighest) } > SOLID_PIXELS)
                        assertTrue(case, pixels.count { near(it, scheme.outline) } > SOLID_PIXELS)
                    }
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
        const val UNDERLINE_SHARE = 0.9
        const val SOLID_PIXELS = 200

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
