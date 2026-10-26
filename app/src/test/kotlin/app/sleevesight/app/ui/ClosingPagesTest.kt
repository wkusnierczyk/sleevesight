package app.sleevesight.app.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FirstRunAcknowledgement
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.ui.home.PAGE_BACK_TAG
import app.sleevesight.app.ui.home.PAGE_CLOSE_TAG
import app.sleevesight.app.ui.home.PAGE_TITLE_TAG
import app.sleevesight.app.ui.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The pages opened from somewhere, the credits and the first-run screen opened again (#549): a bar
 * with the back arrow, read as *Back*, and *Close* at the end, which both go back to where the page
 * was opened from; *Continue* only during the first run, which has neither.
 */
@RunWith(RobolectricTestRunner::class)
class ClosingPagesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    /** As Android records the listener's access turned on or off in Settings. */
    private fun setAccess(granted: Boolean) {
        val component = ComponentName(app, SleevesightListenerService::class.java)
        val listener = component.flattenToString()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", if (granted) listener else "")
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(component, granted)
    }

    /** The app started after the first run, with access on, on Home. */
    private fun launchOnHome(): ActivityScenario<MainActivity> {
        FirstRunAcknowledgement(app).acknowledge()
        setAccess(granted = true)
        return ActivityScenario.launch(MainActivity::class.java).also {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription(text(R.string.home_settings)).performClick()
        compose.openSection(SettingsSection.ABOUT)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.settings_what_leaves)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openFromSettings(row: Int) {
        compose.onNodeWithText(text(row)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun onSettings() {
        compose.onNodeWithText(text(R.string.settings_what_leaves)).performScrollTo().assertIsDisplayed()
        assertEquals(0, count(PAGE_BACK_TAG))
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun countText(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().size

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** The page's bar: its [title], read as a heading, and the back arrow, a button read as *Back*. */
    private fun assertBar(title: Int) {
        compose
            .onNodeWithTag(PAGE_TITLE_TAG)
            .assertIsDisplayed()
            .assertTextEquals(text(title))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose
            .onNodeWithTag(PAGE_BACK_TAG)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(text(R.string.page_back))
            .assert(isButton)
            .assertHasClickAction()
    }

    /** *Close*, at the page's end: a button read by its label. */
    private fun close() {
        compose
            .onNodeWithTag(PAGE_CLOSE_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(text(R.string.page_close))
            .assert(isButton)
            .performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.onNodeWithTag(PAGE_BACK_TAG).performClick()
        compose.waitForIdle()
    }

    /** Android's back, as the system sends it. */
    private fun ActivityScenario<MainActivity>.pressBack() {
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /**
     * Two taps on the node tagged [tag] before the page leaves, as a quick double tap lands; scrolled
     * to first, unless it is in the bar.
     */
    private fun doubleTap(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag == PAGE_CLOSE_TAG) node.performScrollTo()
        node.performTouchInput {
            click()
            click()
        }
        compose.waitForIdle()
    }

    /** The first-run screen as the first run shows it: no bar, no *Close*. */
    private fun assertFirstRunOwn() {
        compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
        assertEquals(0, count(PAGE_BACK_TAG))
        assertEquals(0, count(PAGE_CLOSE_TAG))
    }

    /** The app on its own navigation controller, [nav], with [settings] and [diagnostics] stand-ins. */
    private fun ActivityScenario<MainActivity>.withController(
        diagnostics: @Composable (back: () -> Unit) -> Unit = {},
    ): NavHostController {
        lateinit var nav: NavHostController
        onActivity { activity ->
            activity.setContent {
                nav = rememberNavController()
                SleevesightNavHost(
                    app.graph.notificationAccess,
                    app.graph.firstRun,
                    { true },
                    nav,
                    settings = { Text(SETTINGS_DRAWN) },
                    diagnostics = diagnostics,
                )
            }
        }
        compose.waitForIdle()
        return nav
    }

    @Test
    fun theCreditsHaveABackArrowAndCloseThatReturnToTheSettings() {
        launchOnHome().use {
            openSettings()
            openFromSettings(R.string.credits_title)
            assertBar(R.string.credits_title)
            back()
            onSettings()

            openFromSettings(R.string.credits_title)
            close()
            onSettings()
        }
    }

    @Test
    fun whatLeavesOpenedAgainFromTheSettingsHasABackArrowAndCloseButNoContinue() {
        launchOnHome().use {
            openSettings()
            openFromSettings(R.string.settings_what_leaves)
            assertBar(R.string.home_what_leaves)
            // Its introduction without the first run's "Before you start".
            compose.onNodeWithText(text(R.string.first_run_intro_again)).assertIsDisplayed()
            assertEquals(0, countText(R.string.first_run_intro))
            compose.onNodeWithText(text(R.string.access_on)).performScrollTo().assertIsDisplayed()
            assertEquals(0, countText(R.string.first_run_continue))
            back()
            onSettings()

            openFromSettings(R.string.settings_what_leaves)
            close()
            onSettings()
        }
    }

    @Test
    fun whatLeavesOpenedAgainFromHomeClosesBackToHome() {
        launchOnHome().use {
            compose.onNodeWithText(text(R.string.home_what_leaves)).performScrollTo().performClick()
            compose.waitForIdle()
            assertBar(R.string.home_what_leaves)
            assertEquals(0, countText(R.string.first_run_continue))
            close()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
            assertEquals(0, count(PAGE_BACK_TAG))
        }
    }

    @Test
    fun withAccessTurnedOffHomeOpensThePageWithTheWayToAccessAndClose() {
        launchOnHome().use { scenario ->
            setAccess(granted = false)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText(text(R.string.home_turn_on_access)).performScrollTo().performClick()
            compose.waitForIdle()

            assertBar(R.string.home_what_leaves)
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().assertIsDisplayed()
            close()
            compose.onNodeWithText(text(R.string.home_turn_on_access)).assertIsDisplayed()
        }
    }

    @Test
    fun theFirstRunKeepsContinueWithNoBackArrowOrClose() {
        setAccess(granted = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(text(R.string.app_name)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_intro)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().assertIsDisplayed()
            assertEquals(0, count(PAGE_BACK_TAG))
            assertEquals(0, count(PAGE_CLOSE_TAG))
            assertEquals(0, countText(R.string.page_close))
        }
    }

    @Test
    fun theFirstRunScreenAtTheStartAfterAccessWasTurnedOffHasNoBarAndContinueGoesHome() {
        FirstRunAcknowledgement(app).acknowledge()
        setAccess(granted = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Nothing under it to go back to: the first run's screen, though the first run is past.
            assertFirstRunOwn()
            setAccess(granted = true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFirstRunOwn()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun theFirstRunScreenThatReplacesTheStackHasNoBar() {
        launchOnHome().use { scenario ->
            val nav = scenario.withController()
            compose.onNodeWithText(text(R.string.home_what_leaves)).performScrollTo().performClick()
            compose.waitForIdle()
            assertBar(R.string.home_what_leaves)

            // Home reached before the first run is acknowledged, as a restored back stack could
            // leave it: the first-run screen replaces everything, so nothing is under it.
            app.getSharedPreferences("first_run", Context.MODE_PRIVATE).edit { clear() }
            scenario.onActivity { nav.navigate(Home) }
            compose.waitForIdle()
            assertFirstRunOwn()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aRotationKeepsTheReopenedPageAPage() {
        launchOnHome().use { scenario ->
            openSettings()
            openFromSettings(R.string.settings_what_leaves)
            scenario.recreate()
            compose.waitForIdle()
            assertBar(R.string.home_what_leaves)
            assertEquals(0, countText(R.string.first_run_continue))
            close()
            onSettings()
        }
    }

    @Test
    fun aRotationKeepsTheFirstRunsOwnScreen() {
        setAccess(granted = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            compose.waitForIdle()
            assertFirstRunOwn()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun androidsBackReturnsFromEachPageToWhereItWasOpened() {
        launchOnHome().use { scenario ->
            openSettings()
            openFromSettings(R.string.credits_title)
            scenario.pressBack()
            onSettings()
            openFromSettings(R.string.settings_what_leaves)
            scenario.pressBack()
            onSettings()
        }
    }

    @Test
    fun aDoubleTapOnCloseOrTheBackArrowGoesBackOnce() {
        launchOnHome().use {
            openSettings()
            openFromSettings(R.string.credits_title)
            doubleTap(PAGE_CLOSE_TAG)
            // The settings, not Home under them.
            onSettings()
            openFromSettings(R.string.settings_what_leaves)
            doubleTap(PAGE_BACK_TAG)
            onSettings()
        }
    }

    @Test
    fun theDiagnosticsWayBackReturnsToTheSettingsOnce() {
        launchOnHome().use { scenario ->
            val nav =
                scenario.withController { back ->
                    Button(onClick = back, modifier = Modifier.testTag(PAGE_BACK_TAG)) { Text("Back") }
                }
            scenario.onActivity {
                nav.navigate(SettingsPage)
                nav.navigate(DiagnosticsPage)
            }
            compose.waitForIdle()
            doubleTap(PAGE_BACK_TAG)
            compose.onNodeWithText(SETTINGS_DRAWN).assertIsDisplayed()
        }
    }

    private companion object {
        const val SETTINGS_DRAWN = "Settings drawn"
    }
}
