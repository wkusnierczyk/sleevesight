package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.logo.HEADER_LOGO_HEIGHT
import app.sleevesight.app.ui.logo.LineBox
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's header (#445): it stays while Home scrolls, the app's name scrolls Home back to the top
 * (animated, or at once with the phone's animations off), and the cogwheel opens the settings.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    // On the phone, Compose's window recomposer sets its motion duration scale from the animator
    // duration scale (*Remove animations*); the test rule's recomposer does not read that setting,
    // so the scale is given to it here, read as each animation starts.
    private val motion =
        object : MotionDurationScale {
            var factor = 1f
            override val scaleFactor get() = factor
        }

    @get:Rule
    val compose = createEmptyComposeRule(motion)

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val toTop get() = text(R.string.home_scroll_to_top, text(R.string.app_name))

    private var settingsOpened = 0

    // Home with enough lines under the header to scroll far past a screenful.
    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme {
                        HomeScreen(accessGranted = true, onOpenFirstRun = {}, onOpenSettings = { settingsOpened++ }) {
                            Column {
                                for (i in 0 until LINES) Text("Line $i", modifier = Modifier.testTag("line$i"))
                            }
                        }
                    }
                }
            }
            block()
        }
    }

    private fun scrollToTheEnd() {
        compose.onNodeWithTag("line${LINES - 1}").performScrollTo()
        compose.onNodeWithTag("line0").assertIsNotDisplayed()
    }

    @Test
    fun theHeaderStaysWhileHomeScrolls() =
        show {
            scrollToTheEnd()
            compose.onNodeWithContentDescription(toTop).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(R.string.home_settings)).assertIsDisplayed()
        }

    @Test
    fun theLogoScrollsHomeToTheTop() =
        show {
            scrollToTheEnd()
            compose
                .onNodeWithContentDescription(toTop)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            compose.onNodeWithTag("line0").assertIsDisplayed()
        }

    @Test
    fun theLogoIsReadAsScrollToTopNotAsTheName() =
        show {
            // TalkBack reads the label, and no name a second time.
            compose.onNodeWithContentDescription(toTop).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.app_name)).assertDoesNotExist()
        }

    @Test
    fun theScrollToTopIsAnimated() =
        show {
            scrollToTheEnd()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription(toTop).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("line0").assertIsNotDisplayed()
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("line0").assertIsDisplayed()
        }

    @Test
    fun withAnimationsOffTheScrollToTopIsAtOnce() =
        show {
            motion.factor = 0f
            scrollToTheEnd()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription(toTop).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("line0").assertIsDisplayed()
        }

    @Test
    fun theCogwheelOpensTheSettings() =
        show {
            compose.onNodeWithContentDescription(text(R.string.home_settings)).performClick()
            assertEquals(1, settingsOpened)
        }

    @Test
    fun theTapTargetsAreAtLeastTheMinimum() =
        show {
            val min = with(compose.density) { 48.dp.toPx() }
            for (label in listOf(toTop, text(R.string.home_settings))) {
                val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().touchBoundsInRoot
                assertTrue("$label: $bounds", bounds.width >= min && bounds.height >= min)
            }
        }

    // A very narrow screen at twice the font size: the logo (about 147 dp with its padding, in dp,
    // not scaled with the font) and the cogwheel's 48 dp do not fit in 160 dp, so the logo's box
    // gives way and the logo shrinks to fit (`linePlace`), and the cogwheel keeps its whole target
    // on screen. Native graphics, as when the header held the name's text (#445).
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w160dp-h640dp", fontScale = 2f)
    fun atALargeFontTheCogwheelKeepsItsTarget() =
        show {
            val min = with(compose.density) { 48.dp.toPx() }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val cog = compose.onNodeWithContentDescription(text(R.string.home_settings)).fetchSemanticsNode()
            val bounds = cog.touchBoundsInRoot
            assertTrue("$bounds", bounds.width >= min && bounds.height >= min)
            // Squeezed, the icon was drawn 28 dp wide and its target ran off the screen's edge.
            assertTrue("$bounds in $root", bounds.right <= root.right)
            val logo = compose.onNodeWithContentDescription(toTop).fetchSemanticsNode().boundsInRoot
            assertTrue("$logo before $bounds", logo.right <= bounds.left)
            // The logo's box gave way: narrower than the logo at full size with its padding.
            val full = with(compose.density) { (HEADER_LOGO_HEIGHT * (LineBox.width / LineBox.height) + 16.dp).toPx() }
            assertTrue("$logo shrunk from $full", logo.width < full)
        }

    private companion object {
        const val LINES = 80
    }
}
