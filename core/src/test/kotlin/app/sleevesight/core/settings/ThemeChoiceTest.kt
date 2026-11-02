package app.sleevesight.core.settings

import kotlin.test.Test
import kotlin.test.assertTrue

/** Home's theme button (#636): its switch's default. */
class ThemeChoiceTest {
    @Test
    fun `the theme button is shown by default`() = assertTrue(Settings().themeButton)
}
