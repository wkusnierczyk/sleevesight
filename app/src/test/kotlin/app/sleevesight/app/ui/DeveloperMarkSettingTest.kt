package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import app.sleevesight.app.ui.theme.DEVELOPER_MARK_TAG
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Settings
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

/**
 * *Mark developer items* (#536) under *Developer*: on by default, so the *Developer* section and
 * each player's package name are marked; off, nothing is; and with developer mode off, nothing is
 * marked, and the setting is back on for the next time. The theme follows the stored settings, as
 * `AppGraph.developerMark` has it follow them.
 */
@RunWith(RobolectricTestRunner::class)
class DeveloperMarkSettingTest {
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

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    private fun marks() =
        compose.onAllNodesWithTag(DEVELOPER_MARK_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    // Spotify's package name, inside a mark.
    private fun packageMarked() =
        compose
            .onAllNodes(
                hasText(Settings.SPOTIFY) and hasAnyAncestor(hasTestTag(DEVELOPER_MARK_TAG)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .isNotEmpty()

    // What a screen reader may read as "Developer": the section's heading alone, never the tag.
    private fun developerTexts() =
        compose
            .onAllNodesWithText(text(R.string.settings_section_developer), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    private fun show(block: () -> Unit) {
        val marks =
            settings.settings.map { it.marksDeveloperItems }.stateIn(saves, SharingStarted.Eagerly, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(MutableStateFlow(FontChoice.APP_FONT), developerMarks = marks) {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) {
                            "Label of $it"
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { shown(R.string.settings_title) }
            block()
        }
    }

    @Test
    fun onByDefaultTheDeveloperSectionAndThePackageNamesAreMarked() {
        assertTrue(Settings().markDeveloperItems)
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show {
            compose.openSection(SettingsSection.PLAYERS)
            compose.openSection(SettingsSection.DEVELOPER)
            compose.waitUntil(5_000) { shown(R.string.settings_mark_developer) }
            compose.onNodeWithText(text(R.string.settings_mark_developer_summary)).assertExists()
            compose.onNodeWithText(text(R.string.settings_mark_developer)).performScrollTo().assertIsOn()
            compose.waitUntil(5_000) { packageMarked() }
            // The section and Spotify's package name.
            assertEquals(2, marks())
            assertEquals(1, developerTexts())
        }
    }

    @Test
    fun turnedOffNothingIsMarkedAndTurnedOnAgainTheMarksAreBack() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show {
            compose.openSection(SettingsSection.PLAYERS)
            compose.openSection(SettingsSection.DEVELOPER)
            compose.waitUntil(5_000) { marks() == 2 }

            val row = compose.onNodeWithText(text(R.string.settings_mark_developer))
            row.performScrollTo().performClick()
            compose.waitUntil(5_000) { !settings.now().markDeveloperItems && marks() == 0 }
            row.assertIsOff()
            // The package name still shows, unmarked.
            compose.onNodeWithText(Settings.SPOTIFY, useUnmergedTree = true).assertExists()
            assertEquals(1, developerTexts())

            row.performScrollTo().performClick()
            compose.waitUntil(5_000) { settings.now().markDeveloperItems && marks() == 2 }
        }
    }

    @Test
    fun withDeveloperModeOffNothingIsMarkedAndTheSettingIsBackOn() {
        runBlocking { settings.update { it.copy(developerMode = true, markDeveloperItems = false) } }
        show {
            compose.openSection(SettingsSection.PLAYERS)
            compose.openSection(SettingsSection.DEVELOPER)
            compose.onNodeWithText(text(R.string.settings_mark_developer)).performScrollTo().performClick()
            compose.waitUntil(5_000) { settings.now().markDeveloperItems && marks() == 2 }

            compose.onNodeWithText(text(R.string.settings_developer_mode)).performScrollTo().performClick()
            compose.waitUntil(5_000) { !settings.now().developerMode && marks() == 0 }
            assertTrue("back on for the next time", settings.now().markDeveloperItems)
            val spotify = compose.onAllNodesWithText(Settings.SPOTIFY, useUnmergedTree = true)
            assertEquals(0, spotify.fetchSemanticsNodes().size)
        }
    }
}
