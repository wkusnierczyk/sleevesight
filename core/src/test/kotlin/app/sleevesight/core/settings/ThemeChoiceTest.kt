package app.sleevesight.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Home's theme button (#636): the cycle it moves *Theme* along, and its switch's default. */
class ThemeChoiceTest {
    @Test
    fun `from Light the button gives Dark`() = assertEquals(ThemeChoice.DARK, ThemeChoice.LIGHT.next)

    @Test
    fun `from Dark the button gives System`() = assertEquals(ThemeChoice.SYSTEM, ThemeChoice.DARK.next)

    @Test
    fun `from System the button gives Light`() = assertEquals(ThemeChoice.LIGHT, ThemeChoice.SYSTEM.next)

    @Test
    fun `three taps come back to where they started, through every theme`() {
        for (start in ThemeChoice.entries) {
            val seen = generateSequence(start) { it.next }.take(ThemeChoice.entries.size + 1).toList()
            assertEquals(start, seen.last(), "from $start")
            assertEquals(ThemeChoice.entries.toSet(), seen.toSet(), "from $start")
        }
    }

    @Test
    fun `the theme button is shown by default`() = assertTrue(Settings().themeButton)
}
