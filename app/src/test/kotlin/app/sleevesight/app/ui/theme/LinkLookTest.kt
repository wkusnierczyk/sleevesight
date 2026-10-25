package app.sleevesight.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.sleevesight.app.ui.WebLink
import app.sleevesight.app.ui.theme.TextPart.Link
import app.sleevesight.app.ui.theme.TextPart.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every link in the app as drawn (#442): in the text colour, with a thin line a little below the
 * text, under its glyphs and nothing else, whatever the text's direction, wrapping or size; tinted
 * while focused. Native graphics, on a phone's width and density.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkLookTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val pictures = AccentPictures(compose)

    /**
     * The rows of [picture] that hold a whole-text link's underline: inked across at least
     * [UNDERLINE_SHARE] of the text's inked width, as no row of glyphs is.
     */
    private fun underline(
        picture: Bitmap,
        ink: Color,
        paper: Color,
    ): List<Int> {
        val columns =
            (0 until picture.width).filter { x ->
                (0 until picture.height).any { y -> picture.getPixel(x, y).inked(ink, paper) }
            }
        val span = columns.last() - columns.first() + 1
        return (0 until picture.height).filter { y ->
            picture.row(y).count { it.inked(ink, paper) } >= span * UNDERLINE_SHARE
        }
    }

    @Test
    fun aLinkIsUnderlinedInTheTextColourWithAGapBelowItsDescenders() {
        for (dark in listOf(false, true)) {
            // "privacy" has descenders (p, y) down to the font's lowest.
            pictures.show(dark, { WebLink("https://sleevesight.app/privacy.html", Modifier.testTag(TAG)) }) { scheme ->
                val picture = pictures.picture(TAG)
                assertTrue("no primary colour", picture.all().none { it.near(scheme.primary) })
                val lines = underline(picture, scheme.onSurface, scheme.surface)
                assertTrue("dark=$dark: an underline", lines.isNotEmpty())
                assertTrue(
                    "dark=$dark: the underline in the text colour",
                    lines.any { y ->
                        picture.row(y).count { it.near(scheme.onSurface, tolerance = 40) } > picture.width / 2
                    },
                )
                // Above the underline, a row with no ink at all, then the descenders.
                val glyphs =
                    (0 until lines.first()).filter { y ->
                        y !in lines && picture.row(y).any { it.inked(scheme.onSurface, scheme.surface) }
                    }
                assertTrue("dark=$dark: glyphs above the line", glyphs.isNotEmpty())
                assertTrue(
                    "dark=$dark: a clear row between the descenders (${glyphs.last()}) and the line (${lines.first()})",
                    lines.first() - glyphs.last() > 1,
                )
            }
        }
    }

    /**
     * On each line of the text tagged [TAG] that holds a link, the underline's row runs under every
     * character of the links ([linked]) and under no other: sampled at the middle of each
     * character's box, so a line running on between two stretches of a link fails.
     */
    private fun assertUnderlinedExactly(
        scheme: ColorScheme,
        linked: (Int) -> Boolean,
        minLines: Int = 1,
    ) {
        val picture = pictures.picture(TAG)
        val layout = pictures.layout(TAG)
        assertTrue("no primary colour", picture.all().none { it.near(scheme.primary) })
        var underlined = 0
        for (line in 0 until layout.lineCount) {
            val chars = layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true)
            if (chars.none(linked)) continue
            underlined++
            val y = underlineRow(picture, layout, line, scheme)
            for (offset in chars) {
                val box = layout.getBoundingBox(offset)
                if (box.width < 1f) continue
                val pixel = picture.getPixel(box.center.x.toInt(), y)
                val char = layout.layoutInput.text[offset]
                val inked = pixel.inked(scheme.onSurface, scheme.surface)
                assertEquals("line $line, '$char' at $offset", linked(offset), inked)
            }
        }
        assertTrue("$underlined lines with links", underlined >= minLines)
    }

    /** The row of [line]'s underline: below its baseline, the row with the most ink. */
    private fun underlineRow(
        picture: Bitmap,
        layout: TextLayoutResult,
        line: Int,
        scheme: ColorScheme,
    ): Int =
        (layout.getLineBaseline(line).toInt() + 1 until layout.getLineBottom(line).toInt().coerceAtMost(picture.height))
            .maxBy { y -> picture.row(y).count { it.inked(scheme.onSurface, scheme.surface) } }

    private fun linkedIn(parts: List<TextPart>): (Int) -> Boolean {
        val ranges = mutableListOf<IntRange>()
        var at = 0
        for (part in parts) {
            if (part !is Words) ranges += at until at + part.text.length
            at += part.text.length
        }
        return { offset -> ranges.any { offset in it } }
    }

    @Test
    fun aLinkInATextIsUnderlinedAloneInTheTextColour() {
        val parts = listOf(Words("Read it on "), Link("example.org", URL), Words(", or not."))
        pictures.show(dark = false, {
            LinkedText(parts, MaterialTheme.typography.bodyMedium, Modifier.testTag(TAG))
        }) { scheme -> assertUnderlinedExactly(scheme, linkedIn(parts)) }
    }

    @Test
    fun aWrappedLinkIsUnderlinedOnEachOfItsLines() {
        val parts = listOf(Words("See "), Link("a link long enough to run over several lines", URL), Words(" here."))
        pictures.show(dark = true, {
            LinkedText(parts, MaterialTheme.typography.bodyMedium, Modifier.testTag(TAG).width(120.dp))
        }) { scheme -> assertUnderlinedExactly(scheme, linkedIn(parts), minLines = 2) }
    }

    @Test
    fun aLinkMixingDirectionsIsUnderlinedUnderItsGlyphsAndNotBetweenThem() {
        // Left-to-right text: the link's Hebrew word and the Hebrew after it form one right-to-left
        // run, drawn "abc והד גבא end", so the words after the link sit between its two stretches.
        val parts = listOf(Words("see "), Link("abc אבג", URL), Words(" דהו end"))
        pictures.show(dark = false, {
            LinkedText(parts, MaterialTheme.typography.bodyLarge, Modifier.testTag(TAG))
        }) { scheme -> assertUnderlinedExactly(scheme, linkedIn(parts)) }
    }

    @Test
    fun aLinkInARightToLeftLayoutIsUnderlinedUnderItsGlyphs() {
        val parts = listOf(Words("ראו ב־"), Link("example.org", URL), Words(" עוד"))
        pictures.show(dark = false, {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                LinkedText(parts, MaterialTheme.typography.bodyLarge, Modifier.testTag(TAG))
            }
        }) { scheme -> assertUnderlinedExactly(scheme, linkedIn(parts)) }
    }

    @Test
    fun aTextSizedInEmsDrawsItsLinks() {
        // An em is not a pixel size of its own: the line's size comes from the size it resolves to.
        val parts = listOf(Words("Read it on "), Link("example.org", URL))
        pictures.show(dark = false, {
            LinkedText(parts, TextStyle(fontSize = 1.5.em), Modifier.testTag(TAG))
        }) { scheme -> assertUnderlinedExactly(scheme, linkedIn(parts)) }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun aFocusedLinkIsTinted() {
        val parts = listOf(Words("Read it on "), Link("example.org", URL))
        var input: InputModeManager? = null
        pictures.show(dark = false, {
            input = LocalInputModeManager.current
            LinkedText(parts, MaterialTheme.typography.bodyLarge, Modifier.testTag(TAG))
        }) { scheme ->
            val tint = scheme.onSurface.copy(alpha = 0.12f).compositeOver(scheme.surface)
            // The glyphs' smoothed edges take the tint's grey here and there; the focused link's box
            // is filled with it.
            val link = compose.onNode(hasClickAction() and hasParent(hasTestTag(TAG)), useUnmergedTree = true)
            link.assertIsNotFocused()
            val before = pictures.picture(TAG).all().count { it.near(tint, tolerance = 3) }
            // As with a keyboard or a D-pad: a link takes focus only out of touch mode, where the
            // screen's first link may take it at once. Focus is moved to the link's own node (Tab
            // does not move it in Robolectric's window).
            compose.runOnIdle { checkNotNull(input).requestInputMode(InputMode.Keyboard) }
            link.requestFocus()
            compose.waitForIdle()
            link.assertIsFocused()
            val after = pictures.picture(TAG).all().count { it.near(tint, tolerance = 3) }
            assertTrue("tinted pixels: $before, then focused, $after", after > before + TINTED_PIXELS)
        }
    }

    @Test
    fun linksAreMadeOnlyHere() {
        // Every link is drawn by `LinkedText` or `LinkLabel`, so none takes another look or loses
        // its line. The citations' chips are links of their own kind, styled as chips, and their
        // file underlines the sentences it flags.
        val made =
            listOf(
                """LinkAnnotation\.Url""",
                """LinkAnnotation\.Clickable""",
                """withLink\(""",
                "TextLinkStyles",
                """TextDecoration\.Underline""",
            ).joinToString("|").toRegex()
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.toList()
        assertTrue("sources found", sources.any { it.name == "Accents.kt" })
        val elsewhere =
            sources
                .filter { it.name != "Accents.kt" }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) ->
                            made.containsMatchIn(line) &&
                                !(file.name == "Citations.kt" && "LinkAnnotation.Url" !in line)
                        }.map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
                }
        assertEquals(emptyList<String>(), elsewhere)
    }

    private companion object {
        const val TAG = "text"
        const val URL = "https://example.org"
        const val UNDERLINE_SHARE = 0.9
        const val TINTED_PIXELS = 2000
    }
}
