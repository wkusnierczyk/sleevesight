package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The trial's developer switches (#847), to be removed or kept after it: *Foldable descriptions*,
 * off showing every description in full as before, and *Start folded*, off opening them unfolded,
 * usable only while the first is on. Both on by default.
 *
 * Drawn as on a phone (native graphics): Robolectric's own graphics measure text too narrow for
 * anything to fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DescriptionFoldTrialTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun show(
        section: SettingsSection,
        block: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
            }
        }
        compose.openSection(section)
        block()
    }

    private val spin get() = text(R.string.settings_spin_logo_summary)

    private fun waitFor(words: String) =
        compose.waitUntil(conditionDescription = words, timeoutMillis = WAIT_MS) {
            compose.onAllNodesWithText(words, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun folds() = compose.onAllNodesWithTag(DESCRIPTION_TAG).fetchSemanticsNodes().size

    private fun stateOf(description: String): String =
        compose
            .onNode(hasTestTag(DESCRIPTION_TAG) and hasText(description))
            .fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]

    private fun switch(id: Int) = compose.onNode(isToggleable() and hasText(text(id))).performScrollTo()

    @Test
    fun bothAreOnByDefaultBackOnWithDeveloperModeOffAndStoredUnderTheirKeys() {
        assertTrue(Settings().foldDescriptions)
        assertTrue(Settings().startFolded)
        val off = Settings(developerMode = true, foldDescriptions = false, startFolded = false)
        val reset = off.withoutDeveloperMode()
        assertTrue(reset.foldDescriptions && reset.startFolded)
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(off, prefs)
        assertEquals(false, prefs[booleanPreferencesKey("developer.fold_descriptions")])
        assertEquals(false, prefs[booleanPreferencesKey("developer.start_folded")])
        val read = SettingsCodec.read(prefs)
        assertFalse(read.foldDescriptions || read.startFolded)
        // A file from before them reads both on.
        val before = SettingsCodec.read(mutablePreferencesOf(booleanPreferencesKey("display.intro") to false))
        assertTrue(before.foldDescriptions && before.startFolded)
    }

    @Test
    fun foldableDescriptionsOffShowsEveryDescriptionInFullAndATapOnOneTogglesItsSwitch() {
        runBlocking { settings.update { it.copy(foldDescriptions = false) } }
        show(SettingsSection.APPEARANCE) {
            waitFor(spin)
            assertEquals("no description folds", 0, folds())
            // Read with its switch, and a tap on it toggles it, as before #847.
            compose.onNode(isToggleable() and hasText(spin)).assertExists()
            compose.onNode(hasText(spin), useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().spinLogo }
        }
    }

    @Test
    fun startFoldedOffOpensTheDescriptionsUnfoldedStillFoldingWithATap() {
        runBlocking { settings.update { it.copy(startFolded = false) } }
        show(SettingsSection.APPEARANCE) {
            waitFor(spin)
            val node = compose.onNode(hasTestTag(DESCRIPTION_TAG) and hasText(spin)).performScrollTo()
            assertEquals(text(R.string.settings_description_expanded), stateOf(spin))
            node.performClick()
            compose.waitForIdle()
            assertEquals(text(R.string.settings_description_collapsed), stateOf(spin))
        }
    }

    @Test
    fun theSwitchesAreUnderDeveloperAndStartFoldedIsUsableOnlyWhileFoldableDescriptionsIsOn() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show(SettingsSection.DEVELOPER) {
            waitFor(text(R.string.settings_fold_descriptions_summary))
            switch(R.string.settings_fold_descriptions).assertIsOn()
            switch(R.string.settings_start_folded).assertIsOn().assertIsEnabled()
            assertTrue("descriptions fold", folds() > 0)
            val title = hasText(text(R.string.settings_fold_descriptions)) and hasTestTag(ROW_TITLE_TAG)
            compose
                .onNode(title, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().foldDescriptions }
            switch(R.string.settings_fold_descriptions).assertIsOff()
            switch(R.string.settings_start_folded).assertIsNotEnabled()
            compose.waitUntil(WAIT_MS) { folds() == 0 }
        }
    }
}
