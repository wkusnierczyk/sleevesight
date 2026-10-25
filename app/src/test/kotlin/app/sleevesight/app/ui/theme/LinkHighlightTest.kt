package app.sleevesight.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import app.sleevesight.app.ui.WebLink
import app.sleevesight.app.ui.theme.TextPart.Link
import app.sleevesight.app.ui.theme.TextPart.Words
import app.sleevesight.core.settings.LinkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Link style* (#519) as drawn: with *Highlight*, every link has a faint fill of the text colour
 * behind its characters and none behind the others, and no line under it; with *Underline*, the
 * line and no fill. Whatever draws the link (`LinkedText`, `LinkLabel`, `LinkLook`) follows the
 * style the theme was given. Native graphics, on a phone's width and density.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkHighlightTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val pictures = AccentPictures(compose)

    /** The highlight's colour over [scheme]'s surface: the text colour at its faint alpha. */
    private fun fill(scheme: ColorScheme): Color =
        scheme.onSurface.copy(alpha = HIGHLIGHT_ALPHA).compositeOver(scheme.surface)

    private fun Bitmap.filled(scheme: ColorScheme) = all().count { it.near(fill(scheme), tolerance = 3) }

    /** The rows of [picture] inked across most of its inked width, as an underline is and no glyphs are. */
    private fun underlineRows(
        picture: Bitmap,
        scheme: ColorScheme,
    ): List<Int> {
        val columns =
            (0 until picture.width).filter { x ->
                (0 until picture.height).any { y -> picture.getPixel(x, y).inked(scheme.onSurface, scheme.surface) }
            }
        val span = columns.last() - columns.first() + 1
        return (0 until picture.height).filter { y ->
            picture.row(y).count { it.inked(scheme.onSurface, scheme.surface) } >= span * UNDERLINE_SHARE
        }
    }

    @Test
    fun aHighlightedLinkHasItsFillBehindEachOfItsCharactersAndNoOther() {
        // No punctuation beside the link: the fill runs a little past its glyphs, over the middle
        // of a narrow character next to it.
        val parts = listOf(Words("Read it on "), Link("example.org", URL), Words(" today or not"))
        val linked = 11 until 22
        for (dark in listOf(false, true)) {
            pictures.show(dark, {
                LinkedText(parts, MaterialTheme.typography.bodyLarge, Modifier.testTag(TAG).width(360.dp))
            }, LinkStyle.HIGHLIGHT) { scheme ->
                val picture = pictures.picture(TAG)
                val layout = pictures.layout(TAG)
                val em =
                    layout.layoutInput.style.fontSize.value * layout.layoutInput.density.fontScale *
                        layout.layoutInput.density.density
                // Below the descenders and inside the fill, where the underline would not be.
                val y = (layout.getLineBaseline(0) + em * BELOW_THE_LINE).toInt()
                for (offset in layout.layoutInput.text.indices) {
                    if (layout.layoutInput.text[offset].isWhitespace()) continue
                    val x =
                        layout
                            .getBoundingBox(offset)
                            .center.x
                            .toInt()
                    val pixel = picture.getPixel(x, y)
                    assertEquals(
                        "dark=$dark, '${layout.layoutInput.text[offset]}' at $offset",
                        offset in linked,
                        pixel.near(fill(scheme), tolerance = 3),
                    )
                }
                assertEquals("dark=$dark: no underline", emptyList<Int>(), underlineRows(picture, scheme))
            }
        }
    }

    @Test
    fun underlinedLinksHaveNoFillAndHighlightedOnesNoLine() {
        for (dark in listOf(false, true)) {
            pictures.show(dark, { WebLink("https://sleevesight.app/privacy.html", Modifier.testTag(TAG)) }) { scheme ->
                val picture = pictures.picture(TAG)
                assertTrue("dark=$dark: underlined", underlineRows(picture, scheme).isNotEmpty())
                // The glyphs' smoothed edges take the fill's grey here and there.
                assertTrue(
                    "dark=$dark: no fill, ${picture.filled(scheme)} pixels",
                    picture.filled(scheme) < EDGE_PIXELS,
                )
            }
            pictures.show(dark, {
                WebLink("https://sleevesight.app/privacy.html", Modifier.testTag(TAG))
            }, LinkStyle.HIGHLIGHT) { scheme ->
                val picture = pictures.picture(TAG)
                assertEquals("dark=$dark: no underline", emptyList<Int>(), underlineRows(picture, scheme))
                assertTrue(
                    "dark=$dark: filled, ${picture.filled(scheme)} pixels",
                    picture.filled(scheme) > FILLED_PIXELS,
                )
            }
        }
    }

    @Test
    fun everyWayOfDrawingALinkFollowsTheStyle() {
        pictures.show(false, {
            Column {
                LinkedText(
                    listOf(Words("See "), Link("example.org", URL)),
                    MaterialTheme.typography.bodyMedium,
                    Modifier.testTag("linked"),
                )
                LinkLabel(
                    Link("example.org", URL),
                    "Open",
                    MaterialTheme.typography.bodyMedium,
                    Modifier.testTag("label"),
                )
                Column(Modifier.testTag("look")) { LinkLook("Open website ↗", MaterialTheme.typography.bodyMedium) }
            }
        }, LinkStyle.HIGHLIGHT) { scheme ->
            for (tag in listOf("linked", "label", "look")) {
                val filled = pictures.picture(tag).filled(scheme)
                assertTrue("$tag: filled, $filled pixels", filled > FILLED_PIXELS)
            }
        }
    }

    @Test
    fun aLinkIsMarkedOnItsFirstFrameInEitherStyle() {
        // The clock stopped after the first frame: a mark worked out on a later one, from a layout
        // the first saved for it, is missing.
        for (style in LinkStyle.entries) {
            pictures.show(false, {
                LinkedText(
                    // All of it the link, so the underline runs the whole inked width.
                    listOf(Link("example.org", URL)),
                    MaterialTheme.typography.bodyLarge,
                    Modifier.testTag(TAG),
                )
            }, style, firstFrame = true) { scheme ->
                val picture = pictures.picture(TAG)
                when (style) {
                    LinkStyle.UNDERLINE ->
                        assertTrue(
                            "underlined on the first frame",
                            underlineRows(picture, scheme).isNotEmpty(),
                        )
                    LinkStyle.HIGHLIGHT ->
                        assertTrue(
                            "filled on the first frame, ${picture.filled(scheme)} pixels",
                            picture.filled(scheme) > FILLED_PIXELS,
                        )
                }
            }
        }
    }

    private companion object {
        const val TAG = "text"
        const val URL = "https://example.org"
        const val UNDERLINE_SHARE = 0.9
        const val HIGHLIGHT_ALPHA = 0.10f

        // Between the underline's place (0.24 to 0.29 em below the baseline) and the fill's
        // bottom (0.38 em).
        const val BELOW_THE_LINE = 0.33f
        const val FILLED_PIXELS = 1000
        const val EDGE_PIXELS = 250
    }
}
