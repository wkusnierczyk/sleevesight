package app.sleevesight.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.ui.FirstRun
import app.sleevesight.app.ui.Home
import app.sleevesight.app.ui.SleevesightNavHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationListenerService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirstRunFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val listener = ComponentName(app, SleevesightListenerService::class.java).flattenToString()

    private fun text(id: Int) = app.getString(id)

    /** What Android records when the user turns the listener on or off in Settings. */
    private fun setAccess(granted: Boolean) {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", if (granted) listener else "")
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    /** A launch with Navigation's explicit deep link to [Home], as another app could send it. */
    private fun launchDeepLinkedToHome(): ActivityScenario<MainActivity> {
        // The graph `NavHost` builds has no route, so id 0; a typed destination's id is the hash of
        // its serial name.
        val graphId = 0
        val homeId =
            Home
                .serializer()
                .descriptor.serialName
                .hashCode()
        val intent =
            Intent(app, MainActivity::class.java)
                .putExtra(NavController.KEY_DEEP_LINK_IDS, intArrayOf(graphId, homeId))
        return ActivityScenario.launch(intent)
    }

    /** As after an earlier run in which the user continued past the first-run screen. */
    private fun acknowledgeFirstRun() = FirstRunAcknowledgement(app).acknowledge()

    @Test
    fun withoutAccessTheFirstRunScreenSaysWhatLeavesThePhone() {
        setAccess(granted = false)
        launch().use {
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_to_sources)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_to_ai)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.access_off)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theButtonOpensTheAppsOwnNotificationAccessSwitch() {
        setAccess(granted = false)
        launch().use { scenario ->
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()

            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, started?.action)
            assertEquals(listener, started?.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
        }
    }

    @Test
    fun accessGrantedInSettingsLeadsToHomeOnReturn() {
        setAccess(granted = false)
        launch().use { scenario ->
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()
            setAccess(granted = true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            compose.onNodeWithText(text(R.string.access_on)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()

            // The first-run screen is not left behind Home: nothing in the app handles Back.
            var handlesBack = true
            scenario.onActivity { handlesBack = it.onBackPressedDispatcher.hasEnabledCallbacks() }
            assertFalse(handlesBack)
        }
    }

    @Test
    fun accessGrantedBeforeTheFirstRunStillShowsTheFirstRunScreen() {
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
        // Continuing is remembered: the next start opens on Home.
        launch().use {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun afterTheFirstRunAndWithAccessTheAppOpensOnHome() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun afterTheFirstRunButWithoutAccessTheAppOpensOnTheFirstRunScreen() {
        acknowledgeFirstRun()
        setAccess(granted = false)
        launch().use {
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
        }
    }

    @Test
    fun aDeepLinkToHomeDoesNotSkipTheFirstRunScreen() {
        setAccess(granted = true)
        launchDeepLinkedToHome().use { scenario ->
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()

            // Dropped before the navigation graph read them.
            var left = emptyList<String>()
            scenario.onActivity { activity -> left = DEEP_LINK_EXTRAS.filter { activity.intent.hasExtra(it) } }
            assertEquals(emptyList<String>(), left)
        }
    }

    @Test
    fun homeSendsTheUserToTheFirstRunScreenUntilItIsAcknowledged() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use { scenario ->
            lateinit var nav: NavHostController
            scenario.onActivity { activity ->
                val graph = (activity.application as SleevesightApplication).graph
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(graph.notificationAccess, graph.firstRun, { true }, nav)
                }
            }
            compose.onNodeWithText(text(R.string.home_what_leaves)).performClick()

            // Home reached with the first run not acknowledged, over a stack of Home and FirstRun,
            // as a deep link or a restored back stack could leave it, with no deep-link extras.
            app.getSharedPreferences("first_run", Context.MODE_PRIVATE).edit { clear() }
            scenario.onActivity { nav.navigate(Home) }

            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
            var routes = emptyList<String?>()
            var handlesBack = true
            scenario.onActivity { activity ->
                routes = nav.currentBackStack.value.map { it.destination.route }
                handlesBack = activity.onBackPressedDispatcher.hasEnabledCallbacks()
            }
            assertEquals(listOf(null, FirstRun.serializer().descriptor.serialName), routes)
            assertFalse(handlesBack)

            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
            scenario.onActivity { routes = nav.currentBackStack.value.map { it.destination.route } }
            assertEquals(listOf(null, Home.serializer().descriptor.serialName), routes)
        }
    }

    @Test
    fun deepLinkExtrasDoNoHarmAfterTheFirstRun() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launchDeepLinkedToHome().use {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun homeLeadsBackToTheFirstRunScreen() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_what_leaves)).performClick()
            // Robolectric runs out of touch mode, so Continue takes focus and is scrolled into view
            // when Home opens the first-run screen; the heading is scrolled back to.
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun theAppAsksForTheListenerToBeBoundWhenAccessIsGranted() {
        // An unbound listener (after a force stop or an update) would never start the session layer.
        setAccess(granted = false)
        launch().use { scenario ->
            assertEquals(0, ShadowNotificationListenerService.getRebindRequestCount())
            setAccess(granted = true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())
        }
    }

    private companion object {
        /** `NavController`'s `KEY_DEEP_LINK_*` extras, which `MainActivity` drops. */
        val DEEP_LINK_EXTRAS =
            listOf("Ids", "Args", "Extras", "Intent").map { "android-support-nav:controller:deepLink$it" }
    }
}
