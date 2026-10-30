package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * notification, the first-run screen explains calmly, in its access panel, why Sleevesight asks for
 * notification access and what it does and does not do with it (#708, #738), and Home's prompt to turn
 * the access on, which leads to it, says so in one line; *What it reads* says that Android requires the
 * access, and then that Sleevesight receives no audio.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationAccessStatementTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val whyTitle get() = text(R.string.access_why_title)

    private val statement get() = text(R.string.access_statement)

    private val more get() = text(R.string.access_more)

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
    fun theFirstRunScreenExplainsWhyAccessIsNeededRightAboveTheAccessButton() =
        show({ FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}) }) {
            scrollToStatement()
            val hint = bounds(R.string.access_off_hint)
            val why = bounds(whyTitle)
            val said = bounds(statement)
            val then = bounds(more)
            val button = bounds(R.string.open_access_settings)
            // Between the instruction and the button: the last thing read before the tap.
            assertTrue("the heading follows the hint", hint.bottom <= why.top)
            assertTrue("the statement follows the heading", why.bottom <= said.top)
            assertTrue("where to check follows the statement", said.bottom <= then.top)
            assertTrue("the button follows where to check", then.bottom <= button.top)
        }

    @Test
    fun theFirstRunScreenOpenedAgainSaysItTooWhileAccessIsOff() =
        show({
            FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}, onClose = {})
        }) {
            scrollToStatement()
            val why = bounds(whyTitle)
            val said = bounds(statement)
            val then = bounds(more)
            val button = bounds(R.string.open_access_settings)
            assertTrue("the statement follows the heading", why.bottom <= said.top)
            assertTrue("where to check follows the statement", said.bottom <= then.top)
            assertTrue("the button follows where to check", then.bottom <= button.top)
        }

    /** Each of [ids] is shown once, inside the access panel. */
    private fun assertInPanel(vararg ids: Int) {
        for (id in ids) {
            compose.onAllNodes(hasText(text(id)) and inPanel).assertCountEquals(1)
        }
    }

    private val inPanel get() = hasAnyAncestor(hasTestTag(ACCESS_PANEL_TAG))

    @Test
    fun withAccessOffTheAccessPartIsInItsPanel() =
        show({ FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}) }) {
            assertInPanel(
                R.string.access_off,
                R.string.access_off_hint,
                R.string.access_why_title,
                R.string.access_statement,
                R.string.access_more,
                R.string.open_access_settings,
            )
            // The rest of the screen stays outside it.
            compose.onAllNodes(hasText(text(R.string.first_run_reads_title)) and inPanel).assertCountEquals(0)
        }

    @Test
    fun whenNoSettingsScreenOpensTheErrorStaysInThePanel() =
        show({ FirstRunScreen(accessGranted = false, openAccessSettings = { false }, onContinue = {}) }) {
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()
            compose.waitForIdle()
            assertInPanel(R.string.no_access_settings)
        }

    @Test
    fun withAccessOnTheAccessPartIsInItsPanel() =
        show({ FirstRunScreen(accessGranted = true, openAccessSettings = { true }, onContinue = {}) }) {
            assertInPanel(R.string.access_on, R.string.first_run_continue)
        }

    @Test
    fun withAccessOnThereIsNoDialogToPrepareFor() =
        show({ FirstRunScreen(accessGranted = true, openAccessSettings = { true }, onContinue = {}) }) {
            compose.onNodeWithText(text(R.string.access_on)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(whyTitle).assertDoesNotExist()
            compose.onNodeWithText(statement).assertDoesNotExist()
            compose.onNodeWithText(more).assertDoesNotExist()
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
            compose.onNodeWithText(whyTitle).assertDoesNotExist()
            compose.onNodeWithText(statement).assertDoesNotExist()
            compose.onNodeWithText(short).assertDoesNotExist()
        }

    @Test
    fun theStatementSaysWhatTheAccessIsForWhatAndroidFiltersWhatTheAppIgnoresAndHowToCheck() {
        val said = statement
        val parts =
            listOf(
                "only with notification access",
                "on Android 12 and newer, delivery of notifications to Sleevesight is disabled at the system level",
                "on older versions, Sleevesight ignores all notifications",
                "never reads your messages or contacts",
            )
        for (part in parts) {
            assertTrue("it says \"$part\"", part in said)
        }
        assertTrue("it says how to check", "open source" in more)
    }

    @Test
    fun homesLineSaysTheSameInShort() {
        assertTrue("only to see what is playing", "only to see what is playing" in short)
        assertTrue("none on Android 12 and newer", "receives no notifications on Android 12 and newer" in short)
        assertTrue("ignored on older versions", "ignores them on older versions" in short)
    }

    @Test
    fun whatItReadsSaysAndroidRequiresTheAccessThenThatNoAudioIsReceived() =
        show({ FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}) }) {
            val reads = text(R.string.first_run_reads)
            assertTrue("it says Android requires notification access", "Android requires notification access" in reads)
            val noAudio = text(R.string.first_run_no_audio)
            assertTrue("it says no audio is received: $noAudio", "receives no audio" in noAudio)
            compose.onNodeWithText(noAudio).performScrollTo().assertIsDisplayed()
            // A paragraph of its own, right after What it reads.
            val said = bounds(noAudio)
            assertTrue("it follows What it reads", bounds(reads).bottom <= said.top)
            assertTrue("before What leaves the phone", said.bottom <= bounds(R.string.first_run_leaves_title).top)
        }
}
