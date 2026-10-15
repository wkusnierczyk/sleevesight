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
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.wkusnierczyk.sleevesight.app.session.SleevesightListenerService
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
    fun withAccessTheAppOpensOnHome() {
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun homeLeadsBackToTheFirstRunScreen() {
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_what_leaves)).performClick()
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }
}
