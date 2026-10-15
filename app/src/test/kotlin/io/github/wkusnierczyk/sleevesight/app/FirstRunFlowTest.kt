package io.github.wkusnierczyk.sleevesight.app

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.wkusnierczyk.sleevesight.app.session.SleevesightListenerService
import io.github.wkusnierczyk.sleevesight.app.ui.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
        launchDeepLinkedToHome().use {
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
        }
    }

    @Test
    fun aDeepLinkToHomeAfterTheFirstRunOpensHome() {
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
}
