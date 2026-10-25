package app.sleevesight.app.ui

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.os.Bundle
import android.util.TypedValue
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.idleUntil
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.LastTheme
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.app.ui.theme.isAppInDarkTheme
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * *Theme* (#444): *System*, *Light* or *Dark*, under *Appearance*; the theme follows it, and what
 * draws its own light or dark colours asks the app's choice, not the system's.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeSettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun choose(option: Int) {
        compose.onNodeWithText(text(R.string.settings_theme)).performScrollTo().performClick()
        compose.onNode(hasText(text(option)) and hasAnyAncestor(isDialog())).performClick()
    }

    @Test
    fun theThemeRowSwitchesTheAppLightOrDarkWhateverTheSystemIs() {
        var dark: Boolean? = null
        val themes = settings.settings.map { it.theme }.stateIn(saves, SharingStarted.Eagerly, ThemeChoice.SYSTEM)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    // The theme follows the stored settings, as `AppGraph.theme` has it follow them.
                    SleevesightTheme(MutableStateFlow(FontChoice.APP_FONT), themes) {
                        dark = isAppInDarkTheme()
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            compose.openSection(SettingsSection.APPEARANCE)
            // Robolectric's system is light.
            compose.waitUntil(5_000) { dark == false }

            choose(R.string.settings_theme_dark)
            compose.waitUntil(5_000) { settings.now().theme == ThemeChoice.DARK && dark == true }

            choose(R.string.settings_theme_light)
            compose.waitUntil(5_000) { settings.now().theme == ThemeChoice.LIGHT && dark == false }

            choose(R.string.settings_theme_system)
            compose.waitUntil(5_000) { settings.now().theme == ThemeChoice.SYSTEM }
        }
    }

    @Test
    fun aNewProcessStartsInTheThemeLastReadThenFollowsTheSettings() {
        // As left by an earlier process, whose settings said *Dark*; this one's say the default.
        LastTheme(app).value = ThemeChoice.DARK
        // The settings' first read held off, as `FontSettingsTest` does: while a directory stands
        // where their file goes, every read fails, and is retried.
        val file = app.preferencesDataStoreFile("settings")
        assertTrue(file.mkdirs())
        assertEquals(ThemeChoice.DARK, app.graph.theme.value)
        assertTrue(file.delete())
        idleUntil("the settings' first read") { app.graph.theme.value == ThemeChoice.SYSTEM }
        assertEquals(ThemeChoice.SYSTEM, LastTheme(app).value)
    }

    /** Whether [activity]'s window is light, and its status bar's icons dark, as a light app's are. */
    private fun windowLight(activity: MainActivity): Pair<Boolean, Boolean> {
        val value = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.isLightTheme, value, true)
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return (value.data != 0) to bars.isAppearanceLightStatusBars
    }

    /**
     * A cold start with *Theme* last read as [theme] on a system of the other theme: the window and
     * the bars as they are right after the activity's `onCreate` (`onActivityPostCreated`), before
     * Compose's first frame and its effects, which set the bars again (`SystemBarsFollowTheme`).
     */
    private fun coldStart(theme: ThemeChoice): Pair<Boolean, Boolean> {
        LastTheme(app).value = theme
        // The settings' first read held off, as in `aNewProcessStartsInTheThemeLastRead…`.
        val file = app.preferencesDataStoreFile("settings")
        assertTrue(file.mkdirs())
        var light: Pair<Boolean, Boolean>? = null
        val created =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPostCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    if (activity is MainActivity) light = windowLight(activity)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        app.registerActivityLifecycleCallbacks(created)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { }
            return checkNotNull(light)
        } finally {
            app.unregisterActivityLifecycleCallbacks(created)
            assertTrue(file.delete())
        }
    }

    @Test
    fun aColdStartInDarkOnALightSystemIsDarkFromItsFirstFrame() {
        assertEquals(false to false, coldStart(ThemeChoice.DARK))
    }

    @Test
    @Config(qualifiers = "night")
    fun aColdStartInLightOnADarkSystemIsLightFromItsFirstFrame() {
        assertEquals(true to true, coldStart(ThemeChoice.LIGHT))
    }

    @Test
    fun theThemeIsGivenToTheSystemAsTheAppsNightMode() {
        val uiMode = shadowOf(app.getSystemService(UiModeManager::class.java))
        for ((theme, mode) in listOf(
            ThemeChoice.DARK to UiModeManager.MODE_NIGHT_YES,
            ThemeChoice.LIGHT to UiModeManager.MODE_NIGHT_NO,
            ThemeChoice.SYSTEM to UiModeManager.MODE_NIGHT_AUTO,
        )) {
            runBlocking { app.graph.settings.update { it.copy(theme = theme) } }
            idleUntil("$theme applied") { app.graph.theme.value == theme }
            assertEquals(theme.name, mode, uiMode.applicationNightMode)
        }
    }
}
