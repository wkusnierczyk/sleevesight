package app.sleevesight.app.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
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
 * Home's theme button (#636, #771): before the cogwheel, it shows and names the current theme (a
 * sun, "Theme: light"; a moon, "Theme: dark"; automatic brightness, "Theme: system"), with that
 * name as its tooltip and a 48 dp target. A tap opens a menu of *Light*, *Dark* and *System*, in
 * that order, read as one choice with the current one selected, and "As your phone is" under
 * *System*; the button reads as collapsed or expanded, and its collapse closes the menu. Picking
 * another theme chooses it and closes the menu, while picking the current one, a tap outside the
 * menu or Back closes it with no choice. With *Theme button* off, or before the settings are read,
 * there is no button.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeButtonTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val labels =
        mapOf(
            ThemeChoice.LIGHT to R.string.home_theme_light,
            ThemeChoice.DARK to R.string.home_theme_dark,
            ThemeChoice.SYSTEM to R.string.home_theme_system,
        )

    private fun menuShown() =
        compose.onAllNodes(hasTestTag(themeMenuTag(ThemeChoice.LIGHT))).fetchSemanticsNodes().isNotEmpty()

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

    // The theme button for [theme], its menu opened; the themes chosen go to [chosen].
    private fun menuOpen(
        theme: ThemeChoice,
        chosen: MutableList<ThemeChoice>,
        block: () -> Unit,
    ) = showing(ThemeButtonState(theme) { chosen += it }) {
        compose.onNodeWithContentDescription(text(labels.getValue(theme))).performClick()
        compose.waitUntil(WAIT_MS) { menuShown() }
        block()
    }

    @Test
    fun theButtonIsNamedForTheCurrentTheme() {
        for ((theme, label) in labels) {
            showing(ThemeButtonState(theme) {}) {
                compose.onNodeWithContentDescription(text(label)).assertIsDisplayed()
                // Named for the current theme only: not for the other two.
                for (other in labels.values - label) {
                    assertEquals(
                        theme.name,
                        0,
                        compose.onAllNodesWithContentDescription(text(other)).fetchSemanticsNodes().size,
                    )
                }
            }
        }
        assertEquals("Theme: dark", text(R.string.home_theme_dark))
    }

    @Test
    fun theIconIsTheCurrentTheme() {
        val icons = ThemeChoice.entries.associateWith { ThemeButtonState(it) {}.icon }
        assertEquals(R.drawable.ic_light_mode, icons[ThemeChoice.LIGHT])
        assertEquals(R.drawable.ic_dark_mode, icons[ThemeChoice.DARK])
        assertEquals(R.drawable.ic_brightness_auto, icons[ThemeChoice.SYSTEM])
    }

    @Test
    fun aTapOpensTheMenuOfTheThreeThemesInOrderTheCurrentOneSelected() {
        for (theme in ThemeChoice.entries) {
            val chosen = mutableListOf<ThemeChoice>()
            showing(ThemeButtonState(theme) { chosen += it }) {
                assertTrue(theme.name, !menuShown())
                compose.onNodeWithContentDescription(text(labels.getValue(theme))).performClick()
                compose.waitUntil(WAIT_MS) { menuShown() }
                val tops =
                    listOf(ThemeChoice.LIGHT, ThemeChoice.DARK, ThemeChoice.SYSTEM).map { item ->
                        val node = compose.onNodeWithTag(themeMenuTag(item))
                        if (item == theme) node.assertIsSelected() else node.assertIsNotSelected()
                        // One choice among three: radio buttons, as a screen reader reads them.
                        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                        node.fetchSemanticsNode().boundsInRoot.top
                    }
                assertEquals(theme.name, tops.sorted(), tops)
                compose.onNodeWithText(text(R.string.settings_theme_light)).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.settings_theme_dark)).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.settings_theme_system)).assertIsDisplayed()
                compose.onNodeWithText("As your phone is").assertIsDisplayed()
                // Opening it chooses nothing.
                assertEquals(theme.name, emptyList<ThemeChoice>(), chosen)
            }
        }
    }

    @Test
    fun theButtonReadsAsCollapsedThenExpandedAndCollapseClosesTheMenuWithNoChoice() {
        val chosen = mutableListOf<ThemeChoice>()
        showing(ThemeButtonState(ThemeChoice.DARK) { chosen += it }) {
            val button = compose.onNodeWithContentDescription(text(R.string.home_theme_dark))
            // Collapsed: it offers to expand, not to collapse.
            button.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            button.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Collapse))
            button.performClick()
            compose.waitUntil(WAIT_MS) { menuShown() }
            // Expanded: it offers to collapse, not to expand.
            button.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
            button.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Expand))
            button.performSemanticsAction(SemanticsActions.Collapse)
            compose.waitUntil(WAIT_MS) { !menuShown() }
            assertEquals(emptyList<ThemeChoice>(), chosen)
            button.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        }
    }

    @Test
    fun pickingAnotherThemeChoosesItAndClosesTheMenu() {
        val chosen = mutableListOf<ThemeChoice>()
        menuOpen(ThemeChoice.SYSTEM, chosen) {
            compose.onNodeWithTag(themeMenuTag(ThemeChoice.DARK)).performClick()
            compose.waitUntil(WAIT_MS) { !menuShown() }
            assertEquals(listOf(ThemeChoice.DARK), chosen)
        }
    }

    @Test
    fun pickingTheCurrentThemeClosesTheMenuWithNoChoice() {
        val chosen = mutableListOf<ThemeChoice>()
        menuOpen(ThemeChoice.LIGHT, chosen) {
            compose.onNodeWithTag(themeMenuTag(ThemeChoice.LIGHT)).performClick()
            compose.waitUntil(WAIT_MS) { !menuShown() }
            assertEquals(emptyList<ThemeChoice>(), chosen)
        }
    }

    @Test
    fun aTapOutsideTheMenuClosesItWithNoChoice() {
        val chosen = mutableListOf<ThemeChoice>()
        menuOpen(ThemeChoice.DARK, chosen) {
            // On Home's content, below the header and the menu, as a finger lands on the screen.
            val home = compose.onNodeWithText("Home").fetchSemanticsNode()
            val at = home.positionOnScreen
            tapOnScreen(at.x + home.size.width / 2f, at.y + home.size.height / 2f)
            compose.waitUntil(WAIT_MS) { !menuShown() }
            assertEquals(emptyList<ThemeChoice>(), chosen)
        }
    }

    @Test
    fun backClosesTheMenuWithNoChoice() {
        val chosen = mutableListOf<ThemeChoice>()
        menuOpen(ThemeChoice.SYSTEM, chosen) {
            pressBackOnScreen()
            compose.waitUntil(WAIT_MS) { !menuShown() }
            assertEquals(emptyList<ThemeChoice>(), chosen)
            // Still on Home, the button there.
            compose.onNodeWithContentDescription(text(R.string.home_theme_system)).assertIsDisplayed()
        }
    }

    @Test
    fun theTooltipSaysWhatTheScreenReaderSays() =
        showing(ThemeButtonState(ThemeChoice.LIGHT) {}) {
            val label = text(R.string.home_theme_light)
            compose.onNodeWithContentDescription(label).performTouchInput { longClick() }
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

    @Test
    fun theButtonComesBeforeTheCogwheelAndBothAreFullTargets() =
        showing(ThemeButtonState(ThemeChoice.SYSTEM) {}) {
            val min = with(compose.density) { 48.dp.toPx() }
            val theme = compose.onNodeWithContentDescription(text(R.string.home_theme_system)).fetchSemanticsNode()
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
            for (label in listOf(text(R.string.home_theme_dark), text(R.string.home_settings))) {
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

    // A tap at ([x], [y]) on the screen, as the system delivers it: to the window under it, and to a
    // window that watches for touches outside it, such as the menu's.
    private fun tapOnScreen(
        x: Float,
        y: Float,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        compose.waitForIdle()
    }

    // Android's Back key, as the system delivers it: to the focused window, the menu's while it is open.
    private fun pressBackOnScreen() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            automation.injectInputEvent(KeyEvent(action, KeyEvent.KEYCODE_BACK), true)
        }
        compose.waitForIdle()
    }
}
