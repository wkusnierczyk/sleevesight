package app.sleevesight.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.ui.themeMenuTag
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Home's theme button as the app wires it (#636, #771): `MainActivity`'s Home, with the app's own
 * settings in memory, so the button is there only if the activity passes its feed, and a theme
 * picked in its menu is stored only if the feed saves it. Each theme picked is stored, the app's
 * theme follows it, and the button then names it; the menu, open, stays open across a rotation;
 * *Theme button* off hides the button, and on shows it again.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeButtonWiringTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private fun stored() = app.graph.settings.now()

    private fun menuShown() =
        compose.onAllNodes(hasTestTag(themeMenuTag(ThemeChoice.LIGHT))).fetchSemanticsNodes().isNotEmpty()

    private fun count(label: Int) = compose.onAllNodesWithContentDescription(text(label)).fetchSemanticsNodes().size

    // Home, past the first-run screen with notification access on. Opened as another screen of the
    // app opens it, not from the launcher, so the logo's intro does not play first. The settings
    // in memory, as `ThemeSettingTest`'s night mode test has them, so no wait depends on
    // DataStore's own threads.
    private fun home(block: (ActivityScenario<MainActivity>) -> Unit) {
        app.makeGraph = { AppGraph(it, settingsData = MemoryStore(), settingsDispatcher = Dispatchers.Unconfined) }
        val listener = ComponentName(app, SleevesightListenerService::class.java)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", listener.flattenToString())
        shadowOf(
            app.getSystemService(NotificationManager::class.java),
        ).setNotificationListenerAccessGranted(listener, true)
        FirstRunAcknowledgement(app).acknowledge()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            compose.waitUntil(WAIT_MS) { count(R.string.home_settings) == 1 }
            block(it)
        }
    }

    @Test
    fun eachThemePickedInTheMenuIsStoredAndTheAppFollows() =
        home {
            // *System* by default, and the button names it.
            compose.waitUntil(WAIT_MS) { count(R.string.home_theme_system) == 1 }
            assertEquals(ThemeChoice.SYSTEM, stored().theme)
            var label = R.string.home_theme_system
            for ((theme, named) in listOf(
                ThemeChoice.DARK to R.string.home_theme_dark,
                ThemeChoice.LIGHT to R.string.home_theme_light,
                ThemeChoice.SYSTEM to R.string.home_theme_system,
            )) {
                compose.onNodeWithContentDescription(text(label)).performClick()
                compose.waitUntil(WAIT_MS) {
                    compose.onAllNodes(hasTestTag(themeMenuTag(theme))).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(themeMenuTag(theme)).performClick()
                idleUntil("$theme stored and applied") { stored().theme == theme && app.graph.theme.value == theme }
                compose.waitUntil(WAIT_MS) { count(named) == 1 }
                label = named
            }
        }

    @Test
    fun theMenuOpenStaysOpenAcrossARotationAndAPickThenIsStored() =
        home { scenario ->
            compose.waitUntil(WAIT_MS) { count(R.string.home_theme_system) == 1 }
            compose.onNodeWithContentDescription(text(R.string.home_theme_system)).performClick()
            compose.waitUntil(WAIT_MS) { menuShown() }
            scenario.recreate()
            compose.waitUntil(WAIT_MS) { menuShown() }
            assertEquals(ThemeChoice.SYSTEM, stored().theme)
            compose.onNodeWithTag(themeMenuTag(ThemeChoice.LIGHT)).performClick()
            idleUntil("LIGHT stored and applied") {
                stored().theme == ThemeChoice.LIGHT && app.graph.theme.value == ThemeChoice.LIGHT
            }
            compose.waitUntil(WAIT_MS) { count(R.string.home_theme_light) == 1 }
        }

    @Test
    fun theSwitchHidesAndShowsTheButton() =
        home {
            compose.waitUntil(WAIT_MS) { count(R.string.home_theme_system) == 1 }
            runBlocking { app.graph.settings.update { it.copy(themeButton = false) } }
            compose.waitUntil(WAIT_MS) { count(R.string.home_theme_system) == 0 }
            assertEquals(1, count(R.string.home_settings))
            runBlocking { app.graph.settings.update { it.copy(themeButton = true) } }
            compose.waitUntil(WAIT_MS) { count(R.string.home_theme_system) == 1 }
        }
}
