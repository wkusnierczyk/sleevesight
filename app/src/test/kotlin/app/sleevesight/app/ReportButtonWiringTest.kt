package app.sleevesight.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.ui.report.TESTER_REPORT_HIDDEN_TAG
import app.sleevesight.app.ui.report.TESTER_REPORT_MENU_HIDE_TAG
import app.sleevesight.app.ui.report.TESTER_REPORT_TAG
import app.sleevesight.core.settings.ReportButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The report button's *Hide* as the app wires it (#518): `MainActivity`'s Home, with the app's own
 * settings store, so the button is there only if the activity passes its feed, and *Hide* is
 * stored only if the feed saves it. Stored, it holds after the activity is recreated, also when the
 * recreation comes before the save ends.
 */
@RunWith(RobolectricTestRunner::class)
class ReportButtonWiringTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun stored() =
        runBlocking {
            app.graph.settings.settings
                .first()
        }.reportButton

    private fun buttons() = compose.onAllNodesWithTag(TESTER_REPORT_TAG).fetchSemanticsNodes().size

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // Home, past the first-run screen with notification access on, in developer mode with the
    // button shown. Opened as another screen of the app opens it, not from the launcher, so the
    // logo's intro does not play first.
    private fun home(block: (ActivityScenario<MainActivity>) -> Unit) {
        val listener = ComponentName(app, SleevesightListenerService::class.java)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", listener.flattenToString())
        shadowOf(
            app.getSystemService(NotificationManager::class.java),
        ).setNotificationListenerAccessGranted(listener, true)
        FirstRunAcknowledgement(app).acknowledge()
        runBlocking { app.graph.settings.update { it.copy(developerMode = true, reportButton = ReportButton.SHOW) } }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(5_000) { buttons() == 1 }
            block(scenario)
        }
    }

    private fun hide() {
        compose.onNodeWithTag(TESTER_REPORT_TAG).performClick()
        compose.onNodeWithTag(TESTER_REPORT_MENU_HIDE_TAG).performClick()
    }

    @Test
    fun theMenusHideStoresHideAndItHoldsAfterTheActivityIsRecreated() =
        home { scenario ->
            hide()
            compose.waitUntil(5_000) { shown(TESTER_REPORT_HIDDEN_TAG) }
            assertEquals(ReportButton.HIDE, stored())
            assertEquals(0, buttons())

            scenario.recreate()
            compose.waitForIdle()
            assertEquals(ReportButton.HIDE, stored())
            assertEquals(0, buttons())
        }

    @Test
    fun aHideIsSavedEvenWhenTheActivityIsRecreatedBeforeTheSaveEnds() =
        home { scenario ->
            hide()
            scenario.recreate()
            compose.waitUntil(5_000) { stored() == ReportButton.HIDE }
            compose.waitForIdle()
            assertEquals(0, buttons())
        }
}
