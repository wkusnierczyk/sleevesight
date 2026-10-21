package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.theme.Roboto
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.app.ui.theme.typographyOf
import app.sleevesight.core.settings.FontChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** *Font* (#377): Roboto, bundled, by default; the system font as a setting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontSettingsTest {
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

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun Typography.styles() =
        listOf(
            displayLarge,
            displayMedium,
            displaySmall,
            headlineLarge,
            headlineMedium,
            headlineSmall,
            titleLarge,
            titleMedium,
            titleSmall,
            bodyLarge,
            bodyMedium,
            bodySmall,
            labelLarge,
            labelMedium,
            labelSmall,
        )

    @Test
    fun theAppFontSetsEveryStyleInRobotoAndTheSystemFontLeavesMaterialsDefault() {
        assertTrue(typographyOf(FontChoice.APP_FONT).styles().all { it.fontFamily == Roboto })
        assertEquals(Typography(), typographyOf(FontChoice.SYSTEM_FONT))
        assertTrue(typographyOf(FontChoice.SYSTEM_FONT).styles().none { it.fontFamily == Roboto })
    }

    @Test
    fun theSettingSwitchesTheWholeAppToTheSystemFontAndBack() {
        var typography: Typography? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    // As `MainActivity` draws it: the theme follows the stored settings.
                    SleevesightTheme(settings.settings) {
                        typography = MaterialTheme.typography
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            val appFont = text(R.string.settings_font_app)
            val systemFont = text(R.string.settings_font_system)
            compose.waitUntil(5_000) { shown(appFont) }
            assertEquals(Roboto, typography?.bodyLarge?.fontFamily)

            compose.onNodeWithText(text(R.string.settings_font)).performScrollTo().performClick()
            compose.onNodeWithText(systemFont).performClick()
            compose.waitUntil(5_000) { shown(systemFont) && typography?.bodyLarge?.fontFamily != Roboto }
            assertEquals(FontChoice.SYSTEM_FONT, runBlocking { settings.settings.first() }.font)
            assertEquals(Typography(), typography)

            compose.onNodeWithText(text(R.string.settings_font)).performScrollTo().performClick()
            compose.onNodeWithText(appFont).performClick()
            compose.waitUntil(5_000) { typography?.bodyLarge?.fontFamily == Roboto }
            assertEquals(FontChoice.APP_FONT, runBlocking { settings.settings.first() }.font)
        }
    }

    @Test
    fun theCreditsListTheFontWithItsLicence() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CreditsScreen() } }
            compose.onNodeWithText(text(R.string.credits_roboto)).performScrollTo()
            compose.onNodeWithText("SIL Open Font License 1.1").performScrollTo()
        }
    }

    @Test
    fun theFontsLicenceShipsWithTheApp() {
        val licence =
            app.assets
                .open("licences/roboto-ofl.txt")
                .bufferedReader()
                .use { it.readText() }
        assertTrue(licence.startsWith("Copyright 2011 The Roboto Project Authors"))
        assertTrue(licence.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertNotEquals(-1, licence.indexOf("PERMISSION & CONDITIONS"))
    }
}
