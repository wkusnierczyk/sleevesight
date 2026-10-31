package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import app.sleevesight.app.ui.settings.RESTORE_DISMISSED_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Dismissable
import app.sleevesight.core.settings.Settings
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
 * *Restore dismissed messages* (#759), last under *Appearance*: a button that brings back
 * everything dismissed for good, then a message; disabled while nothing is dismissed.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreDismissedTest {
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

    private fun appearance(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.APPEARANCE)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(RESTORE_DISMISSED_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    private fun button() = compose.onNodeWithTag(RESTORE_DISMISSED_TAG).performScrollTo()

    @Test
    fun nothingIsDismissedByDefault() {
        assertTrue(Settings().dismissed.isEmpty())
    }

    @Test
    fun theButtonIsDisabledWhileNothingIsDismissed() =
        appearance {
            button()
                .assert(hasText(app.getString(R.string.settings_restore_dismissed_button)))
                .assertIsNotEnabled()
        }

    @Test
    fun theButtonReadsByTheRowsName() =
        appearance {
            button().assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(app.getString(R.string.settings_restore_dismissed)),
                ),
            )
            assertEquals(
                1,
                compose
                    .onAllNodesWithText(app.getString(R.string.settings_restore_dismissed_summary))
                    .fetchSemanticsNodes()
                    .size,
            )
        }

    @Test
    fun aTapRestoresEverythingDismissedSaysSoAndDisablesTheButton() {
        runBlocking {
            settings.dismiss(Dismissable.HOME_GUIDE)
            settings.dismiss(Dismissable.PLAYERS_NOTE)
        }
        appearance {
            compose.waitUntil(5_000) {
                runCatching { button().assertIsEnabled() }.isSuccess
            }
            button().performClick()
            compose.waitUntil(5_000) { settings.now().dismissed.isEmpty() }
            val done = app.getString(R.string.settings_restore_dismissed_done)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(done).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { runCatching { button().assertIsNotEnabled() }.isSuccess }
            // Enabled again as soon as anything is dismissed.
            runBlocking { settings.dismiss(Dismissable.PLAYERS_NOTE) }
            compose.waitUntil(5_000) { runCatching { button().assertIsEnabled() }.isSuccess }
        }
    }
}
