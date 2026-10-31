package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.app.idleUntil
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.LastIntro
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.SleevesightTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** *Logo intro at start* (#383): on by default, a switch under *Appearance*, kept for a cold start. */
@RunWith(RobolectricTestRunner::class)
class IntroSettingTest {
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

    @Test
    fun theSwitchUnderAppearanceTurnsTheIntroOffAndOn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            val title = text(R.string.settings_intro)
            compose.openSection(SettingsSection.APPEARANCE)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            val row = compose.onNodeWithText(title).performScrollTo()
            row.assertIsOn()
            row.performClick()
            compose.waitUntil(5_000) { !settings.now().introAtStart }
            row.assertIsOff()
            row.performClick()
            compose.waitUntil(5_000) { settings.now().introAtStart }
        }
    }

    @Test
    fun theSettingIsKeptForTheNextColdStartsFirstFrame() {
        assertTrue("on by default", LastIntro(app).value)
        assertTrue(app.graph.introAtStart.value)
        runBlocking { app.graph.settings.update { it.copy(introAtStart = false) } }
        idleUntil("the setting mirrored") { !LastIntro(app).value }
        assertFalse(app.graph.introAtStart.value)
    }
}
