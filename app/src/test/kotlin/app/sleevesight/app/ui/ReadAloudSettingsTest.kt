package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.ReadAloudMusic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The read-aloud rows under *Insights* (#325): the line on what reads aloud and who may hear its
 * text, then *While reading aloud* as two buttons, *Lower the music* chosen, with what the chosen
 * one does under them; *Read the track line first* and *Read new insights aloud*, off. Each is
 * stored as it is changed, and can be changed with *Show insights* off, as none calls the AI service.
 */
@RunWith(RobolectricTestRunner::class)
class ReadAloudSettingsTest {
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

    private fun showInsights(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            val title = text(R.string.settings_read_new_insights)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            block()
        }
    }

    private fun button(label: Int) = compose.onNode(hasText(text(label)) and isSelectable())

    @Test
    fun theRowsShowTheirDefaultsUnderTheLineOnWhatReads() =
        showInsights {
            compose.onNodeWithText(text(R.string.settings_read_aloud_hint)).performScrollTo()
            button(R.string.settings_read_aloud_lower).performScrollTo().assertIsSelected()
            compose.onNodeWithText(text(R.string.settings_read_aloud_lower_detail)).assertExists()
            compose.onNodeWithText(text(R.string.settings_read_track_line)).performScrollTo().assertIsOff()
            compose.onNodeWithText(text(R.string.settings_read_new_insights)).performScrollTo().assertIsOff()
        }

    @Test
    fun eachChangeIsStored() =
        showInsights {
            button(R.string.settings_read_aloud_pause).performScrollTo().performClick()
            compose.waitUntil(5_000) { settings.now().readAloudMusic == ReadAloudMusic.PAUSE }
            compose.onNodeWithText(text(R.string.settings_read_aloud_pause_detail)).assertExists()

            compose.onNodeWithText(text(R.string.settings_read_track_line)).performScrollTo().performClick()
            compose.waitUntil(5_000) { settings.now().readTrackLineFirst }

            val auto = compose.onNodeWithText(text(R.string.settings_read_new_insights)).performScrollTo()
            auto.performClick()
            compose.waitUntil(5_000) { settings.now().readNewInsightsAloud }
            auto.assertIsOn()
        }

    @Test
    fun theyCanBeChangedWithShowInsightsOff() {
        runBlocking { settings.update { it.copy(writeInsights = false) } }
        showInsights {
            compose.onNodeWithText(text(R.string.settings_read_new_insights)).performScrollTo().performClick()
            compose.waitUntil(5_000) { settings.now().readNewInsightsAloud }
        }
    }
}
