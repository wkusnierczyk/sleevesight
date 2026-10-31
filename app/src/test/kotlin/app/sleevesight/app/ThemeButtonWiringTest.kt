package app.sleevesight.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Home's theme button as the app wires it (#636): `MainActivity`'s Home, with the app's own
 * settings in memory, so the button is there only if the activity passes its feed, and a tap moves
 * *Theme* on only if the feed saves it. Each tap moves the stored theme one step (*System* →
 * *Light* → *Dark* → *System*), the app's theme follows it, and the button then names the next
 * one; *Theme button* off hides the button, and on shows it again.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeButtonWiringTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private fun stored() = app.graph.settings.now()

    private fun count(label: Int) = compose.onAllNodesWithContentDescription(text(label)).fetchSemanticsNodes().size

    // Home, past the first-run screen with notification access on. Opened as another screen of the
    // app opens it, not from the launcher, so the logo's intro does not play first. The settings
    // in memory, as `ThemeSettingTest`'s night mode test has them, so no wait depends on
    // DataStore's own threads.
    private fun home(block: () -> Unit) {
        app.makeGraph = { AppGraph(it, settingsData = MemoryStore()) }
        val listener = ComponentName(app, SleevesightListenerService::class.java)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", listener.flattenToString())
        shadowOf(
            app.getSystemService(NotificationManager::class.java),
        ).setNotificationListenerAccessGranted(listener, true)
        FirstRunAcknowledgement(app).acknowledge()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            compose.waitUntil(5_000) { count(R.string.home_settings) == 1 }
            block()
        }
    }

    @Test
    fun eachTapMovesTheStoredThemeOnAndTheAppFollows() =
        home {
            // *System* by default, so the button offers *Light*.
            compose.waitUntil(5_000) { count(R.string.home_theme_to_light) == 1 }
            assertEquals(ThemeChoice.SYSTEM, stored().theme)
            for ((label, theme, next) in listOf(
                Triple(R.string.home_theme_to_light, ThemeChoice.LIGHT, R.string.home_theme_to_dark),
                Triple(R.string.home_theme_to_dark, ThemeChoice.DARK, R.string.home_theme_to_system),
                Triple(R.string.home_theme_to_system, ThemeChoice.SYSTEM, R.string.home_theme_to_light),
            )) {
                compose.onNodeWithContentDescription(text(label)).performClick()
                idleUntil("$theme stored and applied") { stored().theme == theme && app.graph.theme.value == theme }
                compose.waitUntil(5_000) { count(next) == 1 }
            }
        }

    @Test
    fun theSwitchHidesAndShowsTheButton() =
        home {
            compose.waitUntil(5_000) { count(R.string.home_theme_to_light) == 1 }
            runBlocking { app.graph.settings.update { it.copy(themeButton = false) } }
            compose.waitUntil(5_000) { count(R.string.home_theme_to_light) == 0 }
            assertEquals(1, count(R.string.home_settings))
            runBlocking { app.graph.settings.update { it.copy(themeButton = true) } }
            compose.waitUntil(5_000) { count(R.string.home_theme_to_light) == 1 }
        }
}
