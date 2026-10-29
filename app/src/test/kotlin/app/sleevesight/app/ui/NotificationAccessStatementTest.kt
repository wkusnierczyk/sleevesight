package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Before Android's own screen for notification access, which warns that the app could read every
 * notification, the first-run screen says plainly what that screen will say and what Sleevesight does
 * with the access (#708), and Home's prompt to turn the access on, which leads to it, says so in one
 * line; *What it reads* leads with it.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationAccessStatementTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val statement get() = text(R.string.access_statement)

    private val short get() = text(R.string.home_access_statement)

    private fun show(
        content: @Composable () -> Unit,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { SleevesightTheme { content() } } }
            block()
        }
    }

    private fun bounds(id: Int): Rect = bounds(text(id))

    /**
     * Where [text] lies in the root, unclipped, and without scrolling, so that the places of several
     * nodes compare; the screen is scrolled to the statement first ([scrollToStatement]).
     */
    private fun bounds(text: String): Rect {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private fun scrollToStatement() = compose.onNodeWithText(statement).performScrollTo().assertIsDisplayed()

    @Test
    fun theFirstRunScreenSaysWhatAndroidWillWarnOfRightAboveTheAccessButton() =
        show({ FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}) }) {
            scrollToStatement()
            val hint = bounds(R.string.access_off_hint)
            val said = bounds(statement)
            val button = bounds(R.string.open_access_settings)
            // Between the instruction and the button: the last thing read before the tap.
            assertTrue("the statement follows the hint", hint.bottom <= said.top)
            assertTrue("the button follows the statement", said.bottom <= button.top)
        }

    @Test
    fun theFirstRunScreenOpenedAgainSaysItTooWhileAccessIsOff() =
        show({
            FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}, onClose = {})
        }) {
            scrollToStatement()
            val said = bounds(statement)
            val button = bounds(R.string.open_access_settings)
            assertTrue("the button follows the statement", said.bottom <= button.top)
        }

    @Test
    fun withAccessOnThereIsNoDialogToPrepareFor() =
        show({ FirstRunScreen(accessGranted = true, openAccessSettings = { true }, onContinue = {}) }) {
            compose.onNodeWithText(text(R.string.access_on)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(statement).assertDoesNotExist()
        }

    @Test
    fun homesPromptToTurnOnAccessSaysItInShortBeforeTheButton() =
        show({ HomeScreen(accessGranted = false, onOpenFirstRun = {}, onOpenSettings = {}) }) {
            compose.onNodeWithText(short).performScrollTo().assertIsDisplayed()
            val needs = bounds(R.string.home_access_off)
            val said = bounds(short)
            val button = bounds(R.string.home_turn_on_access)
            assertTrue("the line follows the prompt", needs.bottom <= said.top)
            assertTrue("the button follows the line", said.bottom <= button.top)
            // The button leads to the first-run screen, which says it in full.
            compose.onNodeWithText(statement).assertDoesNotExist()
        }

    @Test
    fun homeWithAccessOnDoesNotSayIt() =
        show({ HomeScreen(accessGranted = true, onOpenFirstRun = {}, onOpenSettings = {}) }) {
            compose.onNodeWithText(text(R.string.home_what_leaves)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(statement).assertDoesNotExist()
            compose.onNodeWithText(short).assertDoesNotExist()
        }

    @Test
    fun theStatementSaysWhatTheDialogShowsWhatTheAppReadsAndHowToCheck() {
        val said = statement
        val parts =
            listOf(
                "all your notifications",
                "Spotify by default",
                "never reads your notifications",
                "never acts on them",
            )
        for (part in parts) {
            assertTrue("it says \"$part\"", part in said)
        }
        assertTrue("it says how to check", "privacy policy" in said && "open source" in said)
    }

    @Test
    fun whatItReadsLeadsWithTheSamePoint() {
        val first = text(R.string.first_run_reads).substringBefore(". ")
        assertTrue("the first sentence says it never reads notifications: $first", "never your notifications" in first)
    }
}
