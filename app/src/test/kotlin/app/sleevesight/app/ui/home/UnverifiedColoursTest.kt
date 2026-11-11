package app.sleevesight.app.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unverified chip's fill and letter (#951): the scheme's `outline`, darkened (or lightened, for a
 * dark letter) until the letter is 4.5:1 on it, as WCAG counts contrast; on the default light scheme
 * about `#6f717a` with white, the default dark one's `#8f9099` as it is with the dark surface.
 */
class UnverifiedColoursTest {
    /** A scheme's outline, surface and text, as named. */
    private class Palette(
        val name: String,
        val outline: Long,
        val surface: Long,
        val onSurface: Long,
    )

    private val light = Palette("default light", 0xFF757780, 0xFFFAF8FF, 0xFF1A1B20)
    private val dark = Palette("default dark", 0xFF8F9099, 0xFF121318, 0xFFE2E2E9)

    // Palettes as the phone's dynamic colours could make them: Material's baseline, a green and a
    // warm one, and one whose outline is light, and a mid grey that neither white nor dark passes on.
    private val dynamic =
        listOf(
            Palette("baseline light", 0xFF79747E, 0xFFFEF7FF, 0xFF1D1B20),
            Palette("baseline dark", 0xFF938F99, 0xFF141218, 0xFFE6E0E9),
            Palette("green light", 0xFF707970, 0xFFF7FBF2, 0xFF181D18),
            Palette("warm dark", 0xFFA08C82, 0xFF1A110E, 0xFFF1DFD9),
            Palette("light outline", 0xFFC4C6D0, 0xFFFFFFFF, 0xFF000000),
            Palette("mid grey", 0xFF808080, 0xFF303030, 0xFFD0D0D0),
        )

    private fun colours(palette: Palette) =
        unverifiedColours(Color(palette.outline), Color(palette.surface), Color(palette.onSurface))

    @Test
    fun theLetterIsAtLeastFourAndAHalfToOneOnTheFillInEveryPalette() {
        for (palette in listOf(light, dark) + dynamic) {
            val (fill, ink) = colours(palette)
            val ratio = contrast(ink, fill)
            assertTrue("${palette.name}: $ratio", ratio >= 4.5f)
            // WCAG's own formula, checked by hand: the lighter's luminance and the darker's, each + 0.05.
            val (a, b) = listOf(ink.luminance(), fill.luminance()).sortedDescending()
            assertEquals(palette.name, (a + 0.05f) / (b + 0.05f), ratio, 0.001f)
        }
    }

    @Test
    fun theDefaultLightGreyIsDarkenedALittleForWhiteAndTheDarkOneIsKept() {
        val (lightFill, lightInk) = colours(light)
        assertEquals(Color.White, lightInk)
        // White on #757780 is under 4.5:1; darkened, but no further than needed (about #6f717a, 4.86:1).
        assertTrue(contrast(Color.White, Color(light.outline)) < 4.5f)
        assertTrue(lightFill.luminance() < Color(light.outline).luminance())
        assertTrue("${contrast(lightInk, lightFill)}", contrast(lightInk, lightFill) < 5.5f)
        val (darkFill, darkInk) = colours(dark)
        assertEquals(Color(dark.outline), darkFill)
        assertEquals(Color(dark.surface), darkInk)
    }

    @Test
    fun aLightOutlineTakesADarkLetter() {
        val palette = dynamic.single { it.name == "light outline" }
        val (fill, ink) = colours(palette)
        assertEquals(Color(palette.onSurface), ink)
        assertEquals(Color(palette.outline), fill)
    }
}
