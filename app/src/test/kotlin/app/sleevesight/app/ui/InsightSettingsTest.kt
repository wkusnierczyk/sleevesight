package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Insights* (#444): *Reactive*, one choice, with *Crossfade* under it only while it is *On skip*,
 * and the mode chosen there what the insight card's toggle restores (#553); *Proactive*, a switch;
 * *Don't repeat artist facts* (#407, #546), a switch; and the rows about writing fixed while *Show
 * insights* is off.
 */
@RunWith(RobolectricTestRunner::class)
class InsightSettingsTest {
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

    private fun inDialog(id: Int) = hasText(text(id)) and hasAnyAncestor(isDialog())

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = 5_000) { condition() }

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            block()
        }
    }

    @Test
    fun crossfadeShowsOnlyWhileReactiveIsOnSkip() =
        show {
            compose.onNodeWithText(text(R.string.settings_write_every_change)).performScrollTo()
            assertTrue(!shown(R.string.settings_crossfade))

            compose.onNodeWithText(text(R.string.settings_write_automatically)).performScrollTo().performClick()
            compose.onNode(inDialog(R.string.settings_write_when_skip)).performClick()
            waitFor("On skip stored") { settings.now().writeAutomatically == WriteAutomatically.WHEN_I_SKIP }
            waitFor("Crossfade shown") { shown(R.string.settings_crossfade) }

            compose.onNodeWithText(text(R.string.settings_write_automatically)).performScrollTo().performClick()
            compose.onNode(inDialog(R.string.settings_write_never)).performClick()
            waitFor("Manual stored") { settings.now().writeAutomatically == WriteAutomatically.NEVER }
            waitFor("Crossfade gone") { !shown(R.string.settings_crossfade) }
            // Under *Manual*, nothing is written away, so *Saver* cannot be changed.
            compose.onNodeWithText(text(R.string.settings_pause_auto_write)).performScrollTo().assertIsNotEnabled()
        }

    @Test
    fun aModeChosenUnderReactiveIsWhatTheInsightCardsToggleRestores() =
        show {
            // The description says the toggle beside each insight turns it on and off too (#553).
            compose
                .onNodeWithText(
                    "Select what triggers writing an insight. Automatic writing can also be turned on and off " +
                        "with the toggle beside each insight.",
                ).performScrollTo()
            compose.onNodeWithText(text(R.string.settings_write_automatically)).performScrollTo().performClick()
            compose.onNode(inDialog(R.string.settings_write_when_skip)).performClick()
            waitFor("On skip stored") { settings.now().writeAutomatically == WriteAutomatically.WHEN_I_SKIP }
            compose.onNodeWithText(text(R.string.settings_write_automatically)).performScrollTo().performClick()
            compose.onNode(inDialog(R.string.settings_write_never)).performClick()
            waitFor("Manual stored") { settings.now().writeAutomatically == WriteAutomatically.NEVER }
            assertEquals(WriteAutomatically.WHEN_I_SKIP, settings.now().lastAutomaticMode)
            assertEquals(WriteAutomatically.WHEN_I_SKIP, settings.now().withAutomaticWriting(true).writeAutomatically)
        }

    @Test
    fun proactiveIsASwitchForTheNextTracksEvidence() =
        show {
            val proactive = compose.onNodeWithText(text(R.string.settings_prepare_next)).performScrollTo()
            proactive.assertIsOn()
            proactive.performClick()
            waitFor("Proactive stored off") { settings.now().prepareNextTrack == PrepareNextTrack.OFF }
            proactive.assertIsOff()
            proactive.performClick()
            waitFor("Proactive stored on") { settings.now().prepareNextTrack == PrepareNextTrack.EVIDENCE }
        }

    @Test
    fun artistFactsOncePerSessionIsASwitchOnByDefault() =
        show {
            val once = compose.onNodeWithText(text(R.string.settings_artist_facts_once)).performScrollTo()
            once.assertIsOn()
            compose.onNodeWithText(text(R.string.settings_artist_facts_once_summary)).assertIsDisplayed()
            once.performClick()
            waitFor("facts on every track stored") { !settings.now().artistFactsOncePerSession }
            once.assertIsOff()
            once.performClick()
            waitFor("facts once per session stored") { settings.now().artistFactsOncePerSession }
        }

    @Test
    fun withShowInsightsOffTheRowsAboutWritingAreFixed() {
        runBlocking { settings.update { it.copy(writeInsights = false) } }
        show {
            compose.onNodeWithText(text(R.string.settings_write_insights)).performScrollTo().assertIsOff()
            compose.onNodeWithText(text(R.string.settings_write_automatically)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.settings_pause_auto_write)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.settings_artist_facts_once)).performScrollTo().assertIsNotEnabled()
        }
    }
}
