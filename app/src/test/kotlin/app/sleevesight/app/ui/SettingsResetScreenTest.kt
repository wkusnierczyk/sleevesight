package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.RESET_CONFIRM_TAG
import app.sleevesight.app.ui.settings.RESET_DIALOG_TAG
import app.sleevesight.app.ui.settings.RESET_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Save and reset* (#960), a section last before *About*, and its *Reset to the defaults*: a dialog
 * listing what goes back to its default and what stays, *Cancel* or *Reset*, then a message.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsResetScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val players = mapOf("org.example.tunes" to true)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun line(
        title: Int,
        now: Int,
        reset: Int,
    ) = app.getString(R.string.settings_reset_line, text(title), text(now), text(reset))

    private fun screen(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            block()
        }
    }

    private fun openDialog() {
        compose.openSection(SettingsSection.SAVE_AND_RESET)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(RESET_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(RESET_TAG).performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(RESET_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun moveSome() =
        runBlocking {
            settings.update {
                it.copy(
                    theme = ThemeChoice.DARK,
                    logoSpeed = LogoSpeed.RPM_45,
                    followedPlayers = players,
                    aiProvider = ProviderId.ANTHROPIC,
                )
            }
        }

    @Test
    fun theSectionIsLastBeforeAboutWithItsDescriptionBesideItsChip() {
        val order = SettingsSection.entries
        assertEquals(order.indexOf(SettingsSection.ABOUT) - 1, order.indexOf(SettingsSection.SAVE_AND_RESET))
        assertEquals("Save and reset", text(R.string.settings_section_save_reset))
        screen {
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(SettingsSection.SAVE_AND_RESET.tag).fetchSemanticsNodes().isNotEmpty()
            }

            fun top(section: SettingsSection) =
                compose
                    .onNodeWithTag(section.tag)
                    .fetchSemanticsNode()
                    .positionInRoot.y
            assertTrue(top(SettingsSection.APPEARANCE) < top(SettingsSection.SAVE_AND_RESET))
            assertTrue(top(SettingsSection.SAVE_AND_RESET) < top(SettingsSection.ABOUT))
            assertTrue(shown("Back to the defaults, or the settings in a file"))
        }
    }

    @Test
    fun theRowSaysWhatAResetDoes() =
        screen {
            compose.openSection(SettingsSection.SAVE_AND_RESET)
            compose.waitUntil(WAIT_MS) { shown("Reset to the defaults") }
            assertTrue(
                shown(
                    "Puts every setting back to what a new install starts with. Your API keys and your history stay.",
                ),
            )
        }

    @Test
    fun theDialogListsEachSettingThatGoesBackAndWhatStays() {
        moveSome()
        screen {
            openDialog()
            assertTrue(shown("Reset settings?"))
            assertTrue(shown("These go back to their defaults:"))
            val theme = line(R.string.settings_theme, R.string.settings_theme_dark, R.string.settings_theme_system)
            assertTrue(shown(theme))
            assertTrue(
                shown(
                    line(
                        R.string.settings_logo_speed,
                        R.string.settings_logo_speed_45,
                        R.string.settings_logo_speed_33,
                    ),
                ),
            )
            assertTrue(shown("These stay as they are:"))
            for (stays in listOf(
                "Your API keys",
                "Your history and kept insights",
                "The players you follow",
                "Which AI service is switched on",
                "Dismissed messages; Appearance → Restore dismissed messages brings them back",
            )) {
                assertTrue(stays, shown(stays))
            }
            // Only the settings that differ: *Font* is at its default.
            val font = compose.onAllNodesWithText(text(R.string.settings_font) + ":", substring = true)
            assertTrue(font.fetchSemanticsNodes().isEmpty())
            assertTrue(!shown("Every setting is at its default already."))
            compose.onNodeWithTag(RESET_CONFIRM_TAG).assertIsEnabled()
        }
    }

    @Test
    fun cancelLeavesTheSettingsAsTheyAre() {
        moveSome()
        val before = settings.settings.now()
        screen {
            openDialog()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(RESET_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
            assertEquals(before, settings.settings.now())
        }
    }

    @Test
    fun resetPutsTheSettingsBackKeepsThePlayersAndTheAiServiceAndSaysSo() {
        moveSome()
        // As stored: a default player not followed is listed as off.
        val followed = settings.settings.now().followedPlayers
        screen {
            openDialog()
            compose.onNodeWithTag(RESET_CONFIRM_TAG).performClick()
            compose.waitUntil(WAIT_MS) { settings.settings.now().theme == ThemeChoice.SYSTEM }
            assertEquals(
                Settings(followedPlayers = followed, aiProvider = ProviderId.ANTHROPIC),
                settings.settings.now(),
            )
            compose.waitUntil(WAIT_MS) { shown("Settings reset to the defaults.") }
            assertTrue(compose.onAllNodesWithTag(RESET_DIALOG_TAG).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun atTheDefaultsTheDialogSaysSoAndResetIsDisabled() =
        screen {
            openDialog()
            assertTrue(shown("Every setting is at its default already."))
            assertTrue(!shown("These go back to their defaults:"))
            assertTrue(shown("These stay as they are:"))
            compose.onNodeWithTag(RESET_CONFIRM_TAG).assertIsNotEnabled()
        }

    @Test
    fun developerModeIsListedAsStayingWhileItIsOn() {
        runBlocking { settings.update { it.copy(developerMode = true, showDiagnostics = true) } }
        screen {
            openDialog()
            assertTrue(shown(text(R.string.settings_developer_mode)))
            val diagnostics =
                line(R.string.settings_diagnostics, R.string.settings_reset_on, R.string.settings_reset_off)
            assertTrue(shown(diagnostics))
        }
    }

    @Test
    fun theMessageIsSaidOnceByTheScreenShownWhenTheResetIsSavedAfterARecreation() {
        moveSome()
        // A recreation, as when the reset changes the theme: a new screen in place of the first.
        var screens by mutableIntStateOf(0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    key(screens) {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            openDialog()
            // The reset's save held until the first screen is gone.
            val gate = CompletableDeferred<Unit>()
            settings.saveOrder.launch(saves) { gate.await() }
            compose.onNodeWithTag(RESET_CONFIRM_TAG).performClick()
            compose.runOnUiThread { screens++ }
            compose.waitForIdle()
            gate.complete(Unit)
            compose.waitUntil(WAIT_MS) { settings.settings.now().theme == ThemeChoice.SYSTEM }
            compose.waitUntil(WAIT_MS) { shown("Settings reset to the defaults.") }
            assertEquals(1, compose.onAllNodesWithText("Settings reset to the defaults.").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aScreenGoneBeforeTheMessageHasShownAndGoneLeavesItToTheNext() {
        moveSome()
        var screens by mutableIntStateOf(0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    key(screens) {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            openDialog()
            // Saved at once; the screen goes while its message shows, as on a recreation.
            compose.onNodeWithTag(RESET_CONFIRM_TAG).performClick()
            compose.waitUntil(WAIT_MS) { shown("Settings reset to the defaults.") }
            compose.runOnUiThread { screens++ }
            compose.waitForIdle()
            compose.waitUntil(WAIT_MS) { shown("Settings reset to the defaults.") }
        }
    }
}
