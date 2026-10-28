package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's theme button (#636): before the cogwheel, it shows and names the theme a tap gives (a moon,
 * "Switch to dark theme", while *Light*; automatic brightness, "Switch to the system theme", while
 * *Dark*; a sun, "Switch to light theme", while *System*), with that name as its tooltip, a 48 dp
 * target, and a tap that moves *Theme* on; with *Theme button* off, or before the settings are
 * read, there is no button.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeButtonTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val labels =
        mapOf(
            ThemeChoice.LIGHT to R.string.home_theme_to_dark,
            ThemeChoice.DARK to R.string.home_theme_to_system,
            ThemeChoice.SYSTEM to R.string.home_theme_to_light,
        )

    // Home's header with [button], the scenario kept open for [block].
    private fun showing(
        button: ThemeButtonState?,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme {
                        HomeScreen(
                            accessGranted = true,
                            onOpenFirstRun = {},
                            onOpenSettings = {},
                            themeButton = button,
                        ) {
                            Text("Home")
                        }
                    }
                }
            }
            block()
        }
    }

    @Test
    fun theButtonIsNamedForTheThemeATapGivesAndATapMovesTheThemeOn() {
        for ((theme, label) in labels) {
            var taps = 0
            showing(ThemeButtonState(theme) { taps++ }) {
                compose.onNodeWithContentDescription(text(label)).assertIsDisplayed().performClick()
                assertEquals(theme.name, 1, taps)
                // Named for the next theme only: not for the other two.
                for (other in labels.values - label) {
                    assertEquals(
                        theme.name,
                        0,
                        compose.onAllNodesWithContentDescription(text(other)).fetchSemanticsNodes().size,
                    )
                }
            }
        }
    }

    @Test
    fun theIconIsTheThemeATapGives() {
        val icons = ThemeChoice.entries.associateWith { ThemeButtonState(it) {}.icon }
        assertEquals(R.drawable.ic_dark_mode, icons[ThemeChoice.LIGHT])
        assertEquals(R.drawable.ic_brightness_auto, icons[ThemeChoice.DARK])
        assertEquals(R.drawable.ic_light_mode, icons[ThemeChoice.SYSTEM])
    }

    @Test
    fun theTooltipSaysWhatTheScreenReaderSays() =
        showing(ThemeButtonState(ThemeChoice.LIGHT) {}) {
            val label = text(R.string.home_theme_to_dark)
            compose.onNodeWithContentDescription(label).performTouchInput { longClick() }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

    @Test
    fun theButtonComesBeforeTheCogwheelAndBothAreFullTargets() =
        showing(ThemeButtonState(ThemeChoice.SYSTEM) {}) {
            val min = with(compose.density) { 48.dp.toPx() }
            val theme = compose.onNodeWithContentDescription(text(R.string.home_theme_to_light)).fetchSemanticsNode()
            val cog = compose.onNodeWithContentDescription(text(R.string.home_settings)).fetchSemanticsNode()
            for (node in listOf(theme, cog)) {
                val bounds = node.touchBoundsInRoot
                assertTrue("$bounds", bounds.width >= min && bounds.height >= min)
            }
            // Side by side, the cogwheel at the end.
            assertTrue(
                "${theme.boundsInRoot} before ${cog.boundsInRoot}",
                theme.boundsInRoot.right <= cog.boundsInRoot.left,
            )
        }

    @Test
    fun withoutTheButtonOnlyTheCogwheelShows() =
        showing(null) {
            compose.onNodeWithContentDescription(text(R.string.home_settings)).assertIsDisplayed()
            for (label in labels.values) {
                assertEquals(0, compose.onAllNodesWithContentDescription(text(label)).fetchSemanticsNodes().size)
            }
        }

    // As `HomeScreenTest`'s narrow screen at twice the font size: the logo gives way, and both
    // buttons keep their whole targets on screen.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w160dp-h640dp", fontScale = 2f)
    fun atALargeFontBothButtonsKeepTheirTargets() =
        showing(ThemeButtonState(ThemeChoice.DARK) {}) {
            val min = with(compose.density) { 48.dp.toPx() }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            for (label in listOf(text(R.string.home_theme_to_system), text(R.string.home_settings))) {
                val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().touchBoundsInRoot
                assertTrue("$label: $bounds", bounds.width >= min && bounds.height >= min)
                assertTrue("$label: $bounds in $root", bounds.left >= root.left && bounds.right <= root.right)
            }
        }

    @Test
    fun theFeedGivesNoButtonBeforeTheSettingsAreReadNorWhileTheSwitchIsOff() {
        val settings = MutableStateFlow<Settings?>(null)
        val feed = ThemeButtonFeed(settings) {}
        var button: ThemeButtonState? = ThemeButtonState(ThemeChoice.SYSTEM) {}
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { button = themeButtonOf(feed) } }
            compose.waitForIdle()
            assertNull(button)

            settings.value = Settings(theme = ThemeChoice.DARK)
            compose.waitForIdle()
            assertEquals(ThemeChoice.DARK, button?.theme)

            settings.value = Settings(theme = ThemeChoice.DARK, themeButton = false)
            compose.waitForIdle()
            assertNull(button)
        }
    }
}
